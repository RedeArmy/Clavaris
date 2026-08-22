package com.clavaris.app.infrastructure.config;

import com.clavaris.identity.application.usecase.issuerefreshtoken.IssueRefreshTokenCommand;
import com.clavaris.identity.application.usecase.issuerefreshtoken.IssueRefreshTokenResult;
import com.clavaris.identity.application.usecase.issuerefreshtoken.IssueRefreshTokenUseCase;
import com.clavaris.identity.domain.model.AccountId;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;

/**
 * BR-ID-03: the initial-issuance half of refresh-token support — wired as one of the two {@link
 * OAuth2TokenGenerator}s a {@code DelegatingOAuth2TokenGenerator} tries in {@link
 * OrganizationAuthorizationServerConfig} (the {@code JwtGenerator} tries first and returns {@code
 * null} for non-JWT token types, so this one only ever fires for {@link
 * OAuth2TokenType#REFRESH_TOKEN}).
 *
 * <p>Deliberately scoped to {@link AuthorizationGrantType#AUTHORIZATION_CODE} only — the refresh
 * grant itself never reaches this class at all: {@link RefreshTokenRotationAuthenticationProvider}
 * replaces SAS's stock {@code OAuth2RefreshTokenAuthenticationProvider} for that grant entirely,
 * calling {@code RotateRefreshTokenUseCase} directly rather than going through a token generator a
 * second time. Returning {@code null} for any other grant type lets {@code
 * DelegatingOAuth2TokenGenerator} correctly report "no refresh token for this request" instead of
 * this class claiming a grant it was never meant to handle.
 *
 * <p>The returned {@link OAuth2RefreshToken}'s raw value is also what SAS's own {@code
 * OAuth2AuthorizationCodeAuthenticationProvider} persists into {@code oauth2_authorization}
 * (TD-SEC-019 — plaintext, a known, already-tracked, framework-forced gap) — redundant with, but
 * never authoritative over, the hashed row {@code IssueRefreshTokenUseCase} writes to {@code
 * refresh_tokens}: {@link RefreshTokenRotationAuthenticationProvider} validates exclusively against
 * that table, never against {@code oauth2_authorization}, so the redundant copy here is inert
 * bookkeeping, not a second source of truth.
 */
final class SessionBackedRefreshTokenGenerator implements OAuth2TokenGenerator<OAuth2RefreshToken> {

  private final IssueRefreshTokenUseCase issueRefreshToken;

  /* package */ SessionBackedRefreshTokenGenerator(
      final IssueRefreshTokenUseCase issueRefreshToken) {
    this.issueRefreshToken = issueRefreshToken;
  }

  // OnlyOneReturn: the early "not my token type/grant" exit is OAuth2TokenGenerator's own
  // documented contract (return null so DelegatingOAuth2TokenGenerator tries the next generator),
  // not a style choice to nest around. LawOfDemeter: OAuth2TokenContext's own accessor chain
  // (getPrincipal()/getRegisteredClient().getTokenSettings()) is the SPI this class implements
  // against, not a real "reaching past a collaborator" concern.
  @SuppressWarnings({"PMD.OnlyOneReturn", "PMD.LawOfDemeter"})
  @Override
  public OAuth2RefreshToken generate(final OAuth2TokenContext context) {
    if (!OAuth2TokenType.REFRESH_TOKEN.equals(context.getTokenType())
        || !AuthorizationGrantType.AUTHORIZATION_CODE.equals(context.getAuthorizationGrantType())) {
      return null;
    }

    final Authentication principal = context.getPrincipal();
    final AccountId accountId = new AccountId(UUID.fromString(principal.getName()));
    final Instant expiresAt =
        Instant.now()
            .plus(context.getRegisteredClient().getTokenSettings().getRefreshTokenTimeToLive());

    final IssueRefreshTokenResult result =
        issueRefreshToken.handle(
            new IssueRefreshTokenCommand(
                accountId, context.getAuthorizedScopes().stream().toList(), expiresAt));

    return new OAuth2RefreshToken(result.rawToken(), Instant.now(), result.expiresAt());
  }
}
