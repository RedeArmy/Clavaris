package com.clavaris.app.infrastructure.config;

import com.clavaris.identity.application.usecase.rotaterefreshtoken.AccountSessionRevoker;
import com.clavaris.identity.domain.model.AccountId;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Component;

/**
 * Implements {@link AccountSessionRevoker} (TD-SEC-031) — the tenant-tier mirror of {@link
 * PlatformAccountSessionRevokerBridge}, same shared {@link SessionRegistry} bean ({@code
 * PlatformDashboardSecurityConfig}, declared once app-wide) and the same {@code expireNow()}
 * mechanics; see that class's own Javadoc for why {@code expireNow()} alone doesn't reject the
 * session's next request — {@code OrganizationAuthorizationServerConfig}'s own {@code
 * sessionConcurrency} wiring (added alongside this class, same reason) is what actually enforces it
 * there.
 *
 * <p>Principal name matches {@code SpringSecurityAuthenticatedSessionEstablisher}'s own {@code
 * accountId.toString()} — the two must agree, or this lookup would never find a live session to
 * expire.
 */
@Component
class AccountSessionRevokerBridge implements AccountSessionRevoker {

  private final SessionRegistry sessionRegistry;

  /* package */ AccountSessionRevokerBridge(final SessionRegistry sessionRegistry) {
    this.sessionRegistry = sessionRegistry;
  }

  @Override
  public void revokeAllSessionsFor(final AccountId accountId) {
    for (final SessionInformation session :
        sessionRegistry.getAllSessions(accountId.value().toString(), false)) {
      session.expireNow();
    }
  }
}
