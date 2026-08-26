package com.clavaris.app.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;

/**
 * TD-SEC-031's own real regression, caught live by {@code RefreshTokenRotationIntegrationTest}: a
 * plain {@code ConcurrentSessionFilter} wiring cleared {@code SecurityContextHolder} for every
 * request carrying an already-expired session cookie, including {@code /oauth2/token} calls whose
 * real authentication (Basic-Auth client credentials) has nothing to do with that cookie — see
 * {@link TenantSessionConcurrencyFilter}'s own Javadoc for the full mechanism. These tests prove
 * the fix at the unit level: the delegate {@code ConcurrentSessionFilter} (and therefore its own
 * {@code doLogout()}/expiry handling) is never even consulted for a machine endpoint, regardless of
 * session state, while a browser-navigable path still gets the real expiry check.
 */
class TenantSessionConcurrencyFilterTest {

  @Test
  void neverConsultsTheSessionRegistryForAMachineEndpointEvenWithAnExpiredSession()
      throws Exception {
    UUID organizationId = UUID.randomUUID();
    SessionRegistry sessionRegistry = mock(SessionRegistry.class);
    TenantSessionConcurrencyFilter filter = new TenantSessionConcurrencyFilter(sessionRegistry);
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/o/" + organizationId + "/oauth2/token");
    request.setSession(new MockHttpSession());
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    // The whole point: a machine endpoint's own request never even asks whether the session is
    // expired — ConcurrentSessionFilter's own doLogout()/SecurityContextHolder-clearing side
    // effect (the real cause of the regression) never has a chance to run.
    verifyNoInteractions(sessionRegistry);
    assertThat(chain.getRequest()).isNotNull();
  }

  @Test
  void invalidatesAnExpiredSessionAndContinuesForABrowserNavigablePath() throws Exception {
    UUID organizationId = UUID.randomUUID();
    SessionRegistry sessionRegistry = mock(SessionRegistry.class);
    org.springframework.mock.web.MockHttpSession session = new MockHttpSession();
    SessionInformation expiredInfo =
        new SessionInformation(new Object(), session.getId(), new Date());
    expiredInfo.expireNow();
    when(sessionRegistry.getSessionInformation(session.getId())).thenReturn(expiredInfo);
    TenantSessionConcurrencyFilter filter = new TenantSessionConcurrencyFilter(sessionRegistry);
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/o/" + organizationId + "/login");
    request.setSession(session);
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    // InvalidateAndContinueSessionExpiredStrategy's own contract: the stale session is gone, but
    // the request still reaches the rest of the chain (the login page itself must still render).
    assertThat(session.isInvalid()).isTrue();
    assertThat(chain.getRequest()).isNotNull();
  }

  @Test
  void passesThroughNormallyWhenNoSessionExistsAtAllOnABrowserNavigablePath() throws Exception {
    UUID organizationId = UUID.randomUUID();
    SessionRegistry sessionRegistry = mock(SessionRegistry.class);
    TenantSessionConcurrencyFilter filter = new TenantSessionConcurrencyFilter(sessionRegistry);
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/o/" + organizationId + "/login");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    verify(sessionRegistry, never()).getSessionInformation(any());
    assertThat(chain.getRequest()).isNotNull();
  }
}
