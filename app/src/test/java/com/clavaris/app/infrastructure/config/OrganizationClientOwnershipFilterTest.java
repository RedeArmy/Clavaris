package com.clavaris.app.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.organization.application.usecase.createworkspace.WorkspaceRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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
class OrganizationClientOwnershipFilterTest {

  private final AccountRepository accounts = mock(AccountRepository.class);
  private final WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
  private final OrganizationClientOwnershipFilter filter =
      new OrganizationClientOwnershipFilter(accounts, workspaces);

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
