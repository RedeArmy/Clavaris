package com.clavaris.app.infrastructure.config;

import com.clavaris.identity.application.usecase.confirmplatformaccountpasswordreset.PlatformAccountSessionRevoker;
import com.clavaris.identity.domain.model.PlatformAccountId;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Component;

/**
 * Implements {@link PlatformAccountSessionRevoker} — see that port's own Javadoc for why this is a
 * {@link SessionRegistry} lookup, not a token-revocation cascade. {@code expireNow()} alone doesn't
 * invalidate the session immediately; it marks it, and {@code ConcurrentSessionFilter} ({@code
 * PlatformDashboardSecurityConfig}'s own {@code sessionConcurrency} wiring) is what actually
 * rejects the next request that session makes.
 */
@Component
class PlatformAccountSessionRevokerBridge implements PlatformAccountSessionRevoker {

  private final SessionRegistry sessionRegistry;

  /* package */ PlatformAccountSessionRevokerBridge(final SessionRegistry sessionRegistry) {
    this.sessionRegistry = sessionRegistry;
  }

  @Override
  public void revokeAllSessionsFor(final PlatformAccountId platformAccountId) {
    for (final SessionInformation session :
        sessionRegistry.getAllSessions(platformAccountId.value().toString(), false)) {
      session.expireNow();
    }
  }
}
