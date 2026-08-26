package com.clavaris.app.infrastructure.config;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.springframework.security.web.session.SessionInformationExpiredEvent;
import org.springframework.security.web.session.SessionInformationExpiredStrategy;

/**
 * TD-SEC-031 (SDE-III review, 2026-08-26): {@code ConcurrentSessionFilter}'s own default {@link
 * SessionInformationExpiredStrategy} writes a terminal, plain-text "session expired" response and
 * never calls {@code filterChain.doFilter(...)} — correct for a chain that is otherwise entirely
 * behind authentication, but this chain's own {@code /o/{organizationId}/login} (and {@code
 * /connect/logout}, TD-SEC-028) are deliberately {@code permitAll()}. Confirmed live: without this,
 * a browser that still holds a cookie for an already-revoked session (password reset, account
 * delete, org delete — this class's own {@link AccountSessionRevokerBridge}) could no longer even
 * reach the login page at all — every request carrying that stale cookie hit this dead end first,
 * before {@code ConcurrentSessionFilter} lets it reach the login controller.
 *
 * <p>Only ever reached for requests {@link TenantSessionConcurrencyFilter} doesn't exempt (i.e.,
 * never this chain's own machine-authenticated endpoints — see that class's own Javadoc for why).
 * Instead: invalidate the local (already-revoked, now-inert) session and let the request continue
 * down the chain as a fresh anonymous request — exactly what a browser hitting the login page
 * fresh, with no session at all, would get. For an actually-protected browser-navigable resource
 * (the login page's own sibling, {@code /oauth2/authorize}), the request still ends up rejected
 * moments later — a clean redirect to login via {@code OrganizationLoginRedirectEntryPoint} — never
 * silently authenticated as anything.
 */
final class InvalidateAndContinueSessionExpiredStrategy
    implements SessionInformationExpiredStrategy {

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  /* package */ InvalidateAndContinueSessionExpiredStrategy() {
    // Intentionally empty — this class holds no state, only the strategy method below.
  }

  // PMD.LawOfDemeter: event.getRequest()/getFilterChain()/getResponse() are the standard
  // SessionInformationExpiredEvent API shape — there is no other way to reach the request this
  // strategy needs to invalidate or the chain it needs to continue.
  @SuppressWarnings("PMD.LawOfDemeter")
  @Override
  public void onExpiredSessionDetected(final SessionInformationExpiredEvent event)
      throws IOException, ServletException {
    final HttpSession session = event.getRequest().getSession(false);
    if (session != null) {
      session.invalidate();
    }
    event.getFilterChain().doFilter(event.getRequest(), event.getResponse());
  }
}
