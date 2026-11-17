package com.clavaris.app.infrastructure.adapter.out.security;

import com.clavaris.identity.application.usecase.revokerefreshtoken.RevokeRefreshTokenCommand;
import com.clavaris.identity.application.usecase.revokerefreshtoken.RevokeRefreshTokenUseCase;
import com.clavaris.identity.domain.model.OrganizationId;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2TokenRevocationAuthenticationToken;

/**
 * TD-SEC-100: makes {@code /oauth2/revoke} reach the refresh tokens Clavaris actually issues.
 *
 * <p>A refresh token's source of truth here is the {@code refresh_tokens} table (BR-ID-03), and
 * rotation never writes it to Spring Authorization Server's {@code oauth2_authorization}, which is
 * all SAS's own {@code OAuth2TokenRevocationAuthenticationProvider} searches. So the stock provider
 * found nothing for a refresh token, answered 200 as RFC 7009 §2.2 requires for an unknown token,
 * and the token went on minting new ones. Found by the JobSeeker integration spike, 2026-11-16.
 *
 * <p>Tried before the stock provider. When the presented value is a refresh token of the
 * Organization whose endpoint received the call, its Session is ended (see {@link
 * RevokeRefreshTokenUseCase}) and the request is complete. Anything else (an access token, an
 * unknown value, another Organization's token, a request whose client is not authenticated) returns
 * {@code null}, which hands it on to the stock provider unchanged: access and ID tokens revoke as
 * before and the client-authentication failures keep their own answers. The {@code token_type_hint}
 * is only a hint (RFC 7009 §2.1), so it is not used to skip the lookup.
 */
// BR-ID-32: a revoked refresh token never mints another token; its whole Session ends.
public final class RefreshTokenRevocationAuthenticationProvider implements AuthenticationProvider {

  private final RevokeRefreshTokenUseCase revokeRefreshToken;

  public RefreshTokenRevocationAuthenticationProvider(
      final RevokeRefreshTokenUseCase revokeRefreshToken) {
    this.revokeRefreshToken = revokeRefreshToken;
  }

  @Override
  public Authentication authenticate(final Authentication authentication) {
    final OAuth2TokenRevocationAuthenticationToken request =
        (OAuth2TokenRevocationAuthenticationToken) authentication;
    if (!(request.getPrincipal() instanceof OAuth2ClientAuthenticationToken client)
        || !client.isAuthenticated()) {
      return null;
    }
    final Optional<UUID> organizationId = CurrentOrganizationContext.currentOrganizationId();
    if (organizationId.isEmpty()) {
      return null;
    }
    final boolean revoked =
        revokeRefreshToken.handle(
            new RevokeRefreshTokenCommand(
                request.getToken(), new OrganizationId(organizationId.get())));
    if (!revoked) {
      return null;
    }
    return new OAuth2TokenRevocationAuthenticationToken(
        new OAuth2RefreshToken(request.getToken(), Instant.now(), null), client);
  }

  @Override
  public boolean supports(final Class<?> authentication) {
    return OAuth2TokenRevocationAuthenticationToken.class.isAssignableFrom(authentication);
  }
}
