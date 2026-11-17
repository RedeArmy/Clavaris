package com.clavaris.app.infrastructure.adapter.in.web.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.organization.application.usecase.createworkspace.WorkspaceRepository;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.WebhookEndpointRepository;
import com.clavaris.webhook.domain.model.WebhookEndpoint;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * ADR-0023 §3: the security-critical class of this feature — proves the fail-closed allowlist
 * behaviour directly, without needing a real OAuth2 token exchange (the full live proof of that is
 * {@code OrganizationClientCrossTenantIntegrationTest}).
 */
// BR-PLATFORM-06: an OrganizationClient is confined to its allowlist and its own Organization.
class OrganizationClientOwnershipFilterTest {

  private final AccountRepository accounts = mock(AccountRepository.class);
  private final WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
  private final WebhookEndpointRepository webhookEndpoints = mock(WebhookEndpointRepository.class);
  private final OrganizationClientOwnershipFilter filter =
      new OrganizationClientOwnershipFilter(accounts, workspaces, webhookEndpoints);

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void passesThroughUnchangedWhenNoOrganizationIdClaimIsPresent_platformClientToken()
      throws Exception {
    authenticateWithClaims(java.util.Map.of("sub", "some-platform-client"));
    MockHttpServletRequest request =
        put("/api/v1/admin/organizations/" + UUID.randomUUID() + "/rate-limit-policy");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertThat(chain.getRequest()).as("the request reached downstream — never blocked").isNotNull();
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void allowsADirectRouteWhenTheClaimMatchesThePathOrganizationId() throws Exception {
    UUID organizationId = UUID.randomUUID();
    authenticateWithClaims(
        java.util.Map.of("sub", "sk_test_x", "organization_id", organizationId.toString()));
    MockHttpServletRequest request =
        put("/api/v1/admin/organizations/" + organizationId + "/rate-limit-policy");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertThat(chain.getRequest()).isNotNull();
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void rejectsADirectRouteWhenTheClaimTargetsADifferentOrganization() throws Exception {
    UUID tokenOrganizationId = UUID.randomUUID();
    UUID pathOrganizationId = UUID.randomUUID();
    authenticateWithClaims(
        java.util.Map.of("sub", "sk_test_x", "organization_id", tokenOrganizationId.toString()));
    MockHttpServletRequest request =
        put("/api/v1/admin/organizations/" + pathOrganizationId + "/rate-limit-policy");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(chain.getRequest()).as("must never reach the controller once rejected").isNull();
  }

  @Test
  void rejectsAnEndpointWithNoRegisteredResolverEvenThoughTheClaimIsPresent_failClosed()
      throws Exception {
    authenticateWithClaims(
        java.util.Map.of("sub", "sk_test_x", "organization_id", UUID.randomUUID().toString()));
    // A real admin-API endpoint (organization deletion) deliberately NOT in the v1 allowlist.
    MockHttpServletRequest request =
        post("/api/v1/admin/organizations/" + UUID.randomUUID() + ":delete");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertThat(response.getStatus())
        .as("no resolver matched — fail closed, never a silent pass")
        .isEqualTo(403);
  }

  @Test
  void allowsAOneHopAccountRouteWhenTheResolvedOwnerMatchesTheClaim() throws Exception {
    UUID organizationId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    when(accounts.findOrganizationIdById(new AccountId(accountId)))
        .thenReturn(Optional.of(new OrganizationId(organizationId)));
    authenticateWithClaims(
        java.util.Map.of("sub", "sk_test_x", "organization_id", organizationId.toString()));
    MockHttpServletRequest request = post("/api/v1/admin/accounts/" + accountId + ":impersonate");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(200);
  }

  // TD-SEC-061 (closed): the read equivalent of the three write routes on this same resource was
  // missing from the allowlist — this filter fails closed, so an OrganizationClient token with
  // write access here got an inexplicable 403 on the one read of an otherwise complete CRUD set.
  @Test
  void allowsAOneHopWorkspaceGetMembersRouteWhenTheResolvedOwnerMatchesTheClaim() throws Exception {
    UUID organizationId = UUID.randomUUID();
    UUID workspaceId = UUID.randomUUID();
    when(workspaces.findOrganizationIdById(workspaceId)).thenReturn(Optional.of(organizationId));
    authenticateWithClaims(
        java.util.Map.of("sub", "sk_test_x", "organization_id", organizationId.toString()));
    MockHttpServletRequest request = get("/api/v1/admin/workspaces/" + workspaceId + "/members");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void rejectsAOneHopAccountRouteWhenTheResolvedOwnerIsADifferentOrganization() throws Exception {
    UUID accountOrganizationId = UUID.randomUUID();
    UUID tokenOrganizationId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    when(accounts.findOrganizationIdById(new AccountId(accountId)))
        .thenReturn(Optional.of(new OrganizationId(accountOrganizationId)));
    authenticateWithClaims(
        java.util.Map.of("sub", "sk_test_x", "organization_id", tokenOrganizationId.toString()));
    MockHttpServletRequest request = post("/api/v1/admin/accounts/" + accountId + ":impersonate");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertThat(response.getStatus())
        .as("the exact cross-tenant impersonation attempt this feature exists to prevent")
        .isEqualTo(403);
  }

  // ---- TD-SEC-101: a consumer erases, suspends and manages its webhooks without the
  // platform-wide credential, and only inside its own Organization.

  static Stream<Arguments> accountRoutes() {
    return Stream.of(
        Arguments.of("POST", "/api/v1/admin/accounts/%s:delete"),
        Arguments.of("POST", "/api/v1/admin/accounts/%s:suspend"),
        Arguments.of("POST", "/api/v1/admin/accounts/%s:reactivate"));
  }

  static Stream<Arguments> webhookEndpointRoutes() {
    return Stream.of(
        Arguments.of("POST", "/api/v1/admin/webhook-endpoints/%s:activate"),
        Arguments.of("POST", "/api/v1/admin/webhook-endpoints/%s:deactivate"),
        Arguments.of("POST", "/api/v1/admin/webhook-endpoints/%s:rotate-secret"),
        Arguments.of("GET", "/api/v1/admin/webhook-endpoints/%s/deliveries"),
        Arguments.of(
            "POST",
            "/api/v1/admin/webhook-endpoints/%s/deliveries/" + UUID.randomUUID() + ":replay"));
  }

  private static MockHttpServletRequest request(final String method, final String uri) {
    MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
    request.setRequestURI(uri);
    return request;
  }

  private int statusFor(final String method, final String uri) throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request(method, uri), response, new MockFilterChain());
    return response.getStatus();
  }

  @ParameterizedTest
  @MethodSource("accountRoutes")
  void anAccountRouteIsOpenForAnAccountOfTheTokensOwnOrganizationOnly(
      final String method, final String template) throws Exception {
    UUID own = UUID.randomUUID();
    UUID ownAccount = UUID.randomUUID();
    UUID foreignAccount = UUID.randomUUID();
    when(accounts.findOrganizationIdById(new AccountId(ownAccount)))
        .thenReturn(Optional.of(new OrganizationId(own)));
    when(accounts.findOrganizationIdById(new AccountId(foreignAccount)))
        .thenReturn(Optional.of(new OrganizationId(UUID.randomUUID())));
    authenticateWithClaims(java.util.Map.of("sub", "sk_test_x", "organization_id", own.toString()));

    assertThat(statusFor(method, template.formatted(ownAccount))).isEqualTo(200);
    assertThat(statusFor(method, template.formatted(foreignAccount)))
        .as("another Organization's account")
        .isEqualTo(403);
    assertThat(statusFor(method, template.formatted(UUID.randomUUID())))
        .as("an account that does not exist")
        .isEqualTo(403);
    assertThat(statusFor(method, template.formatted("not-a-uuid")))
        .as("an id that is not a UUID")
        .isEqualTo(403);
  }

  @ParameterizedTest
  @MethodSource("webhookEndpointRoutes")
  void aWebhookEndpointRouteIsOpenForAnEndpointOfTheTokensOwnOrganizationOnly(
      final String method, final String template) throws Exception {
    UUID own = UUID.randomUUID();
    WebhookEndpoint ownEndpoint = anEndpointOf(own);
    WebhookEndpoint foreignEndpoint = anEndpointOf(UUID.randomUUID());
    when(webhookEndpoints.findById(ownEndpoint.id())).thenReturn(Optional.of(ownEndpoint));
    when(webhookEndpoints.findById(foreignEndpoint.id())).thenReturn(Optional.of(foreignEndpoint));
    authenticateWithClaims(java.util.Map.of("sub", "sk_test_x", "organization_id", own.toString()));

    assertThat(statusFor(method, template.formatted(ownEndpoint.id()))).isEqualTo(200);
    assertThat(statusFor(method, template.formatted(foreignEndpoint.id())))
        .as("another Organization's endpoint")
        .isEqualTo(403);
    assertThat(statusFor(method, template.formatted(UUID.randomUUID())))
        .as("an endpoint that does not exist")
        .isEqualTo(403);
  }

  @ParameterizedTest
  @MethodSource("webhookEndpointRoutes")
  void aPlatformClientTokenIsStillUnscopedOnTheNewRoutes(final String method, final String template)
      throws Exception {
    authenticateWithClaims(java.util.Map.of("sub", "some-platform-client"));

    assertThat(statusFor(method, template.formatted(UUID.randomUUID()))).isEqualTo(200);
  }

  private static WebhookEndpoint anEndpointOf(final UUID organizationId) {
    return WebhookEndpoint.register(
        organizationId,
        "https://consumer.example.com/hook",
        null,
        java.util.List.of("x"),
        "secret");
  }

  private static MockHttpServletRequest put(final String uri) {
    MockHttpServletRequest request = new MockHttpServletRequest("PUT", uri);
    request.setRequestURI(uri);
    return request;
  }

  private static MockHttpServletRequest post(final String uri) {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
    request.setRequestURI(uri);
    return request;
  }

  private static MockHttpServletRequest get(final String uri) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
    request.setRequestURI(uri);
    return request;
  }

  private void authenticateWithClaims(final java.util.Map<String, Object> claims) {
    Jwt jwt =
        Jwt.withTokenValue("token-value")
            .header("alg", "RS256")
            .claims(claimsMap -> claimsMap.putAll(claims))
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .build();
    SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
  }
}
