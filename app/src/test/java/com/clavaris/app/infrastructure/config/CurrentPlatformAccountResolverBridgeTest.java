package com.clavaris.app.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Security finding (SDE-III review, 2026-08-22): before this fix, {@code resolve()} trusted any
 * authenticated principal's name as a {@code PlatformAccountId}, regardless of which tier
 * authenticated it — this is the regression test for the {@code ROLE_PLATFORM_ACCOUNT} authority
 * check that closes it. See {@code PlatformDashboardSecurityConfig}'s own sibling fix (the primary
 * control; this is the defense-in-depth layer).
 */
class CurrentPlatformAccountResolverBridgeTest {

  private final CurrentPlatformAccountResolverBridge bridge =
      new CurrentPlatformAccountResolverBridge();

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void resolvesThePlatformAccountIdWhenTheAuthorityIsPresent() {
    UUID platformAccountId = UUID.randomUUID();
    authenticateAs(
        platformAccountId.toString(), new SimpleGrantedAuthority("ROLE_PLATFORM_ACCOUNT"));

    assertThat(bridge.resolve(new MockHttpServletRequest())).contains(platformAccountId);
  }

  @Test
  void doesNotResolveATenantAccountSessionEvenThoughItsPrincipalNameIsAValidUuid() {
    // The exact authority SpringSecurityAuthenticatedSessionEstablisher (tenant tier) grants —
    // authenticated, a real UUID principal name, but never ROLE_PLATFORM_ACCOUNT.
    UUID tenantAccountId = UUID.randomUUID();
    authenticateAs(
        tenantAccountId.toString(),
        FactorGrantedAuthority.withAuthority(FactorGrantedAuthority.PASSWORD_AUTHORITY)
            .issuedAt(Instant.now())
            .build());

    assertThat(bridge.resolve(new MockHttpServletRequest())).isEmpty();
  }

  @Test
  void doesNotResolveAnAuthenticatedPrincipalWithNoAuthoritiesAtAll() {
    authenticateAs(UUID.randomUUID().toString());

    assertThat(bridge.resolve(new MockHttpServletRequest())).isEmpty();
  }

  @Test
  void doesNotResolveWhenThereIsNoAuthenticationAtAll() {
    assertThat(bridge.resolve(new MockHttpServletRequest())).isEmpty();
  }

  private void authenticateAs(final String principalName, final GrantedAuthority... authorities) {
    final Authentication authentication =
        UsernamePasswordAuthenticationToken.authenticated(
            principalName, null, List.of(authorities));
    final SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(authentication);
    SecurityContextHolder.setContext(context);
  }
}
