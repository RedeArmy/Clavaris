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
 * TD-FUT-042, OIDC Core §5.4: the {@code phone} scope asks for the {@code phone_number} and {@code
 * phone_number_verified} claims. The scope did not exist in this system, and nothing wrote the
 * claims, so a client requesting {@code phone} got nothing.
 *
 * <p>Same shape as {@link EmailClaimsCustomizer}: added to the ID token only, and only when the
 * {@code phone} scope was authorized; {@code /userinfo} returns them through {@code
 * WorkspaceAwareOidcUserInfoMapper}'s existing {@code phone} gate, which was already in place.
 *
 * <p>Two choices worth stating. The claims are omitted entirely for an Account with no phone number
 * rather than sent empty, since "not provided" and "empty" are different statements to a relying
 * party. And {@code phone_number_verified} is always {@code false}: Clavaris stores the number an
 * End-User or operator typed and has no flow that proves control of it, so claiming otherwise would
 * be a false statement a consumer might act on.
 */
public class PhoneClaimsCustomizer implements OAuth2TokenCustomizer<JwtEncodingContext> {

  private static final String PHONE_SCOPE = "phone";

  private final AccountRepository accounts;

  public PhoneClaimsCustomizer(final AccountRepository accounts) {
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
    if (!hasEndUser || !context.getAuthorizedScopes().contains(PHONE_SCOPE)) {
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
        .flatMap(Account::phoneNumber)
        .ifPresent(
            phoneNumber ->
                context
                    .getClaims()
                    .claim("phone_number", phoneNumber)
                    .claim("phone_number_verified", false));
  }
}
