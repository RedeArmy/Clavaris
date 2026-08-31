package com.clavaris.app.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.identity.domain.model.AccountId;
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

class CurrentAccountResolverBridgeTest {

  private final CurrentAccountResolverBridge bridge = new CurrentAccountResolverBridge();
  private final HttpServletRequest request = new MockHttpServletRequest();

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void resolvesTheAccountIdFromAnAuthenticatedTenantSession() {
    AccountId accountId = AccountId.newId();
    authenticateAs(accountId.value().toString(), "ROLE_ACCOUNT");

    assertThat(bridge.resolve(request)).contains(accountId);
  }

  @Test
  void isEmptyWhenUnauthenticated() {
    assertThat(bridge.resolve(request)).isEmpty();
  }

  @Test
  void isEmptyForAPlatformTierSessionEvenThoughItIsAuthenticated() {
    // Defense in depth (this class's own Javadoc) — a PlatformAccount session must never resolve
    // as a tenant Account, same class of cross-tier confusion CurrentPlatformAccountResolverBridge
    // already guards against in the other direction.
    authenticateAs(UUID.randomUUID().toString(), "ROLE_PLATFORM_ACCOUNT");

    assertThat(bridge.resolve(request)).isEmpty();
  }

  @Test
  void isEmptyWhenThePrincipalNameIsNotAValidUuid() {
    authenticateAs("not-a-uuid", "ROLE_ACCOUNT");

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
