package com.clavaris.app.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.common.application.port.SecurityMetricsRecorder;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tools.jackson.databind.ObjectMapper;

/**
 * Unit-level coverage of the header-matching/claim-outcome-branching logic itself, against a mocked
 * {@link IdempotencyKeyStore} — {@link RedisIdempotencyKeyStoreTest} already covers the real Redis
 * mechanics; this class is about proving THIS filter reacts correctly to each outcome, not
 * re-proving Redis atomicity. Same split {@link AntiAbuseRateLimitingFilterTest} already
 * establishes against its own {@link RateLimiter}.
 */
class IdempotencyKeyFilterTest {

  private static final IdempotencyKeyHasher KEY_HASHER = new IdempotencyKeyHasher("test-secret");
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final SecurityMetricsRecorder NO_OP_METRICS = (name, tags) -> {};

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void passesThroughUnchangedWhenNoIdempotencyKeyHeaderIsPresent() throws Exception {
    IdempotencyKeyStore store = mock(IdempotencyKeyStore.class);
    IdempotencyKeyFilter filter =
        new IdempotencyKeyFilter(store, KEY_HASHER, OBJECT_MAPPER, NO_OP_METRICS);
    authenticateAsClient("platform-client-a");
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/api/v1/admin/organizations");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertThat(chain.getRequest())
        .as("no header — must reach the real chain unchanged")
        .isNotNull();
    verify(store, never()).claim(anyString(), anyString());
  }

  @Test
  void passesThroughUnchangedForANonPostMethodEvenWithTheHeaderPresent() throws Exception {
    IdempotencyKeyStore store = mock(IdempotencyKeyStore.class);
    IdempotencyKeyFilter filter =
        new IdempotencyKeyFilter(store, KEY_HASHER, OBJECT_MAPPER, NO_OP_METRICS);
    authenticateAsClient("platform-client-a");
    MockHttpServletRequest request =
        new MockHttpServletRequest("PUT", "/api/v1/admin/organizations/org-1/rate-limit-policy");
    request.addHeader("Idempotency-Key", "retry-1");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertThat(chain.getRequest()).isNotNull();
    verify(store, never()).claim(anyString(), anyString());
  }

  @Test
  void passesThroughUnchangedWhenNoClientIsAuthenticated() throws Exception {
    // Defensive-only case (this filter is wired after authentication in the real chain) — never
    // reachable in production, but must degrade safely rather than throw.
    IdempotencyKeyStore store = mock(IdempotencyKeyStore.class);
    IdempotencyKeyFilter filter =
        new IdempotencyKeyFilter(store, KEY_HASHER, OBJECT_MAPPER, NO_OP_METRICS);
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/api/v1/admin/organizations");
    request.addHeader("Idempotency-Key", "retry-1");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertThat(chain.getRequest()).isNotNull();
    verify(store, never()).claim(anyString(), anyString());
  }

  @Test
  void aClaimedRequestReachesTheRealChainAndCachesASuccessfulResponse() throws Exception {
    IdempotencyKeyStore store = mock(IdempotencyKeyStore.class);
    when(store.claim(anyString(), anyString())).thenReturn(IdempotencyClaimResult.claimed());
    IdempotencyKeyFilter filter =
        new IdempotencyKeyFilter(store, KEY_HASHER, OBJECT_MAPPER, NO_OP_METRICS);
    authenticateAsClient("platform-client-a");
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/api/v1/admin/organizations");
    request.addHeader("Idempotency-Key", "retry-1");
    request.setContent("{\"name\":\"Acme\"}".getBytes(StandardCharsets.UTF_8));
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain =
        new MockFilterChain() {
          @Override
          public void doFilter(
              final ServletRequest servletRequest, final ServletResponse servletResponse)
              throws IOException, ServletException {
            super.doFilter(servletRequest, servletResponse);
            ((HttpServletResponse) servletResponse).setStatus(201);
            ((HttpServletResponse) servletResponse).setContentType("application/json");
            servletResponse
                .getOutputStream()
                .write("{\"id\":\"org-1\"}".getBytes(StandardCharsets.UTF_8));
          }
        };

    filter.doFilter(request, response, chain);

    assertThat(chain.getRequest()).as("a claimed request must reach the real chain").isNotNull();
    assertThat(response.getStatus()).isEqualTo(201);
    assertThat(response.getContentAsString()).isEqualTo("{\"id\":\"org-1\"}");
    // IdempotentResponse overrides equals() to compare its own byte[] body by content, not
    // reference — see its own Javadoc — so eq(...) here genuinely proves the cached entry, not a
    // false negative a record's default array-by-reference equals() would otherwise produce.
    verify(store)
        .complete(
            anyString(),
            anyString(),
            eq(new IdempotentResponse(201, "application/json", "{\"id\":\"org-1\"}".getBytes())));
  }

  @Test
  void aClaimedRequestThatFailsWith5xxReleasesTheClaimInsteadOfCachingIt() throws Exception {
    IdempotencyKeyStore store = mock(IdempotencyKeyStore.class);
    when(store.claim(anyString(), anyString())).thenReturn(IdempotencyClaimResult.claimed());
    IdempotencyKeyFilter filter =
        new IdempotencyKeyFilter(store, KEY_HASHER, OBJECT_MAPPER, NO_OP_METRICS);
    authenticateAsClient("platform-client-a");
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/api/v1/admin/organizations");
    request.addHeader("Idempotency-Key", "retry-1");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain =
        new MockFilterChain() {
          @Override
          public void doFilter(
              final ServletRequest servletRequest, final ServletResponse servletResponse) {
            ((HttpServletResponse) servletResponse).setStatus(500);
          }
        };

    filter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(500);
    verify(store, never()).complete(anyString(), anyString(), any());
    verify(store).release(anyString());
  }

  @Test
  void anInProgressClaimBlocksWith409WithoutReachingTheRealChain() throws Exception {
    IdempotencyKeyStore store = mock(IdempotencyKeyStore.class);
    when(store.claim(anyString(), anyString())).thenReturn(IdempotencyClaimResult.inProgress());
    IdempotencyKeyFilter filter =
        new IdempotencyKeyFilter(store, KEY_HASHER, OBJECT_MAPPER, NO_OP_METRICS);
    authenticateAsClient("platform-client-a");
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/api/v1/admin/organizations");
    request.addHeader("Idempotency-Key", "retry-1");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertThat(chain.getRequest())
        .as("a still-in-flight duplicate must never reach the real chain")
        .isNull();
    assertThat(response.getStatus()).isEqualTo(409);
  }

  @Test
  void aConflictingReuseBlocksWith422WithoutReachingTheRealChain() throws Exception {
    IdempotencyKeyStore store = mock(IdempotencyKeyStore.class);
    when(store.claim(anyString(), anyString())).thenReturn(IdempotencyClaimResult.conflict());
    IdempotencyKeyFilter filter =
        new IdempotencyKeyFilter(store, KEY_HASHER, OBJECT_MAPPER, NO_OP_METRICS);
    authenticateAsClient("platform-client-a");
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/api/v1/admin/organizations");
    request.addHeader("Idempotency-Key", "retry-1");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertThat(chain.getRequest()).isNull();
    assertThat(response.getStatus()).isEqualTo(422);
  }

  @Test
  void aReplayableClaimReturnsTheExactCachedResponseWithoutReachingTheRealChain() throws Exception {
    IdempotencyKeyStore store = mock(IdempotencyKeyStore.class);
    when(store.claim(anyString(), anyString()))
        .thenReturn(
            IdempotencyClaimResult.replay(
                new IdempotentResponse(201, "application/json", "{\"id\":\"org-1\"}".getBytes())));
    IdempotencyKeyFilter filter =
        new IdempotencyKeyFilter(store, KEY_HASHER, OBJECT_MAPPER, NO_OP_METRICS);
    authenticateAsClient("platform-client-a");
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/api/v1/admin/organizations");
    request.addHeader("Idempotency-Key", "retry-1");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertThat(chain.getRequest()).as("a replay must never re-run the real mutation").isNull();
    assertThat(response.getStatus()).isEqualTo(201);
    assertThat(response.getContentType()).isEqualTo("application/json");
    assertThat(response.getContentAsString()).isEqualTo("{\"id\":\"org-1\"}");
  }

  @Test
  void scopesTheRedisKeyByTheAuthenticatedClientIdNotOnlyTheRawHeaderValue() throws Exception {
    // Two different clients using the exact same self-chosen "retry-1" key must never collide.
    IdempotencyKeyStore store = mock(IdempotencyKeyStore.class);
    when(store.claim(anyString(), anyString())).thenReturn(IdempotencyClaimResult.claimed());
    IdempotencyKeyFilter filter =
        new IdempotencyKeyFilter(store, KEY_HASHER, OBJECT_MAPPER, NO_OP_METRICS);

    authenticateAsClient("client-a");
    MockHttpServletRequest requestA =
        new MockHttpServletRequest("POST", "/api/v1/admin/organizations");
    requestA.addHeader("Idempotency-Key", "retry-1");
    filter.doFilter(requestA, new MockHttpServletResponse(), new MockFilterChain());

    SecurityContextHolder.clearContext();
    authenticateAsClient("client-b");
    MockHttpServletRequest requestB =
        new MockHttpServletRequest("POST", "/api/v1/admin/organizations");
    requestB.addHeader("Idempotency-Key", "retry-1");
    filter.doFilter(requestB, new MockHttpServletResponse(), new MockFilterChain());

    ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
    verify(store, times(2)).claim(keys.capture(), anyString());
    assertThat(keys.getAllValues().get(0)).isNotEqualTo(keys.getAllValues().get(1));
  }

  private static void authenticateAsClient(final String clientId) {
    Jwt jwt =
        Jwt.withTokenValue("token-value")
            .header("alg", "RS256")
            .claims(claims -> claims.putAll(Map.of("sub", clientId)))
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .build();
    // The 2-arg constructor (even with an empty authority list), not the 1-arg one: only this one
    // marks the resulting token isAuthenticated() == true, confirmed against JwtAuthenticationToken
    // 's own source — the 1-arg constructor never calls setAuthenticated(true) at all, which
    // RateLimitIdentifiers.authenticatedPlatformClientId's own null-check depends on.
    SecurityContextHolder.getContext()
        .setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
  }
}
