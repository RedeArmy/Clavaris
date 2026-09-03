package com.clavaris.app.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.identity.domain.model.PlatformAccountId;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** TD-FUT-026: mirror of {@code CurrentAccountResolverBridgeTest}, other tier. */
class IdentityCurrentPlatformAccountResolverBridgeTest {

  private final IdentityCurrentPlatformAccountResolverBridge bridge =
      new IdentityCurrentPlatformAccountResolverBridge();
  private final HttpServletRequest request = new MockHttpServletRequest();

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void resolvesThePlatformAccountIdFromAnAuthenticatedPlatformSession() {
    PlatformAccountId platformAccountId = PlatformAccountId.newId();
    authenticateAs(platformAccountId.value().toString(), "ROLE_PLATFORM_ACCOUNT");

    assertThat(bridge.resolve(request)).contains(platformAccountId);
  }

  @Test
  void isEmptyWhenUnauthenticated() {
    assertThat(bridge.resolve(request)).isEmpty();
  }

  @Test
  void isEmptyForATenantTierSessionEvenThoughItIsAuthenticated() {
    authenticateAs(UUID.randomUUID().toString(), "ROLE_ACCOUNT");

    assertThat(bridge.resolve(request)).isEmpty();
  }

  @Test
  void isEmptyWhenThePrincipalNameIsNotAValidUuid() {
    authenticateAs("not-a-uuid", "ROLE_PLATFORM_ACCOUNT");

    assertThat(bridge.resolve(request)).isEmpty();
  }

  private void authenticateAs(final String principalName, final String authority) {
    Authentication authentication =
        UsernamePasswordAuthenticationToken.authenticated(
            principalName, null, List.of(new SimpleGrantedAuthority(authority)));
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(authentication);
    SecurityContextHolder.setContext(context);
  }
}
