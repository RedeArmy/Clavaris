package com.clavaris.app.infrastructure.adapter.out.security;

import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import java.util.UUID;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

/**
 * OIDC Core §5.4: the {@code email} scope asks for the {@code email} and {@code email_verified}
 * claims. Nothing wrote them before this class, so a client that requested {@code email} still got
 * neither on its ID token nor on {@code /userinfo}: {@code WorkspaceAwareOidcUserInfoMapper} only
 * forwards what the ID token already carries, and it carried no email.
 *
 * <p>Added to the ID token only (OIDC puts identity claims there and on {@code /userinfo}; an
 * access token is an opaque credential for a resource server and should stay lean), and only when
 * the {@code email} scope was authorized. {@code /userinfo} then returns them through the mapper's
 * existing {@code email} scope gate, with no change to it. The verification flag is always sent,
 * {@code false} included, so a relying party can tell "unverified" from "not provided".
 */
public class EmailClaimsCustomizer implements OAuth2TokenCustomizer<JwtEncodingContext> {

  // The email scope and the email claim share a name (OIDC Core 5.4).
  private static final String EMAIL_SCOPE = "email";

  private final AccountRepository accounts;

  public EmailClaimsCustomizer(final AccountRepository accounts) {
    this.accounts = accounts;
  }

  @Override
  public void customize(final JwtEncodingContext context) {
    if (!OidcParameterNames.ID_TOKEN.equals(context.getTokenType().getValue())) {
      return;
    }
    final AuthorizationGrantType grantType = context.getAuthorizationGrantType();
    final boolean hasEndUser =
        AuthorizationGrantType.AUTHORIZATION_CODE.equals(grantType)
            || AuthorizationGrantType.REFRESH_TOKEN.equals(grantType);
    if (!hasEndUser || !context.getAuthorizedScopes().contains(EMAIL_SCOPE)) {
      return;
    }
    final UUID accountId;
    try {
      accountId = UUID.fromString(context.getPrincipal().getName());
    } catch (final IllegalArgumentException _) {
      return;
    }
    accounts
        .findById(new AccountId(accountId))
        .ifPresent(
            (final Account account) ->
                context
                    .getClaims()
                    .claim(EMAIL_SCOPE, account.email().value())
                    .claim("email_verified", account.emailVerifiedAt().isPresent()));
  }
}
