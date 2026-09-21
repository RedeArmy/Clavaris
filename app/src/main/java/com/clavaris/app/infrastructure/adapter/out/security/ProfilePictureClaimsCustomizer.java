package com.clavaris.app.infrastructure.adapter.out.security;

import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

/**
 * ADR-0026: adds {@code picture}/{@code name}/{@code given_name}/{@code family_name}/{@code
 * preferred_username} to a real Account login's ID token — the population side of the OIDC {@code
 * profile} claim group {@code WorkspaceAwareOidcUserInfoMapper} already forwards to {@code
 * /userinfo} but (per that class's own Javadoc) has never actually had anything to forward, since
 * nothing wrote these claims onto the ID token before this class existed. Gated on the {@code
 * profile} scope actually having been requested/authorized — the OIDC-standard-conformant behavior
 * (CLAUDE.md §2's own conformance target), and the same gate {@code
 * WorkspaceAwareOidcUserInfoMapper} already applies for {@code /userinfo} independently;
 * scope-gating here too means a client that never requested {@code profile} never sees these claims
 * on the ID token either, not just filtered out one layer later.
 *
 * <p>{@code picture} is always populated once an Account is found and the scope is granted — never
 * conditional on a real uploaded photo existing, since {@code GetAccountAvatarService}'s own avatar
 * endpoint always resolves to something (a real upload, a social provider's own external URL, or a
 * generated-initials default) at that exact same stable URL.
 *
 * <p>Same "own TransactionTemplate/self-invocation" concerns don't apply here — this customizer
 * only reads, and composes into {@code OrganizationAuthorizationServerConfig}'s own single {@code
 * JwtGenerator} customizer slot exactly the way {@code WorkspaceRoleClaimsCustomizer} already does
 * (see that class's own Javadoc for why it's constructed directly there rather than a
 * {@code @Component}).
 */
// PMD.LongVariable: hasAnAccountPrincipal/clavarisBaseUrl name exactly what they are — same
// convention WorkspaceRoleClaimsCustomizer's own identical suppression documents.
// PMD.OnlyOneReturn: customize() has several genuinely independent early exits (wrong grant type,
// scope not granted, malformed principal name, unknown Account) — same "each needs its own exit"
// rationale WorkspaceRoleClaimsCustomizer's own identical suppression documents.
@SuppressWarnings({"PMD.LongVariable", "PMD.OnlyOneReturn"})
public class ProfilePictureClaimsCustomizer implements OAuth2TokenCustomizer<JwtEncodingContext> {

  private static final String PROFILE_SCOPE = "profile";

  private final AccountRepository accounts;
  private final String clavarisBaseUrl;

  public ProfilePictureClaimsCustomizer(
      final AccountRepository accounts, final String clavarisBaseUrl) {
    this.accounts = accounts;
    this.clavarisBaseUrl = clavarisBaseUrl;
  }

  @SuppressWarnings("PMD.LawOfDemeter") // context.getClaims()/getPrincipal() is the standard SAS
  // API shape — same rationale WorkspaceRoleClaimsCustomizer's own identical suppression documents.
  @Override
  public void customize(final JwtEncodingContext context) {
    final AuthorizationGrantType grantType = context.getAuthorizationGrantType();
    final boolean hasAnAccountPrincipal =
        AuthorizationGrantType.AUTHORIZATION_CODE.equals(grantType)
            || AuthorizationGrantType.REFRESH_TOKEN.equals(grantType);
    if (!hasAnAccountPrincipal) {
      return;
    }
    if (!context.getAuthorizedScopes().contains(PROFILE_SCOPE)) {
      return;
    }

    final Authentication principal = context.getPrincipal();
    final UUID accountId;
    try {
      accountId = UUID.fromString(principal.getName());
    } catch (final IllegalArgumentException _) {
      return;
    }

    final Optional<Account> maybeAccount = accounts.findById(new AccountId(accountId));
    if (maybeAccount.isEmpty()) {
      return;
    }
    final Account account = maybeAccount.get();

    final JwtClaimsSet.Builder claims = context.getClaims();
    claims.claim(
        "picture",
        clavarisBaseUrl + "/o/" + account.organizationId().value() + "/avatars/" + accountId);
    account.firstName().ifPresent(firstName -> claims.claim("given_name", firstName));
    account.lastName().ifPresent(lastName -> claims.claim("family_name", lastName));
    fullName(account).ifPresent(name -> claims.claim("name", name));
    account.username().ifPresent(username -> claims.claim("preferred_username", username.value()));
  }

  private Optional<String> fullName(final Account account) {
    final String name =
        (account.firstName().orElse("") + " " + account.lastName().orElse("")).strip();
    return name.isEmpty() ? Optional.empty() : Optional.of(name);
  }
}
