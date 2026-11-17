package com.clavaris.app.infrastructure.adapter.out.security;

import com.clavaris.identity.application.usecase.revokesessionbysid.RevokeSessionBySidCommand;
import com.clavaris.identity.application.usecase.revokesessionbysid.RevokeSessionBySidUseCase;
import com.clavaris.identity.domain.model.OrganizationId;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.server.authorization.oidc.authentication.OidcLogoutAuthenticationToken;

/**
 * TD-SEC-100: RP-Initiated Logout ends the Session's refresh tokens as well as the browser session.
 *
 * <p>Wraps Spring Authorization Server's own logout provider, which validates the {@code
 * id_token_hint} and the client; only a request that provider accepted gets here, so a forged hint
 * revokes nothing. Then the Session named by the hint's {@code sid} is ended with every refresh
 * token of it: before, logging out left the token the client held able to mint new ones. The
 * wrapped provider's own result is returned untouched, so the redirect and the browser-session
 * handling are exactly what they were.
 */
public final class SessionRevokingLogoutAuthenticationProvider implements AuthenticationProvider {

  private final AuthenticationProvider delegate;
  private final RevokeSessionBySidUseCase revokeSession;

  public SessionRevokingLogoutAuthenticationProvider(
      final AuthenticationProvider delegate, final RevokeSessionBySidUseCase revokeSession) {
    this.delegate = delegate;
    this.revokeSession = revokeSession;
  }

  @Override
  public Authentication authenticate(final Authentication authentication) {
    final Authentication accepted = delegate.authenticate(authentication);
    if (accepted instanceof OidcLogoutAuthenticationToken logout) {
      final OidcIdToken idToken = logout.getIdToken();
      final Optional<UUID> organizationId = CurrentOrganizationContext.currentOrganizationId();
      final String sid = idToken == null ? null : idToken.getClaimAsString("sid");
      if (sid != null && organizationId.isPresent()) {
        revokeSession.handle(
            new RevokeSessionBySidCommand(sid, new OrganizationId(organizationId.get())));
      }
    }
    return accepted;
  }

  @Override
  public boolean supports(final Class<?> authentication) {
    return delegate.supports(authentication);
  }
}
