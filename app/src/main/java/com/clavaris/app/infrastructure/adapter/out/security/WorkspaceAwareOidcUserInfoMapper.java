package com.clavaris.app.infrastructure.adapter.out.security;

import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.domain.model.AccountId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.oidc.authentication.OidcUserInfoAuthenticationContext;

/**
 * {@code /userinfo} response mapper — replaces SAS's own {@code DefaultOidcUserInfoMapper} so the
 * {@code workspace_id}/{@code workspace_role}/{@code workspace_permissions} claims (ADR-0027 adds
 * the third) {@link WorkspaceRoleClaimsCustomizer} adds to the ID token actually reach {@code
 * /userinfo}, not just the token response.
 *
 * <p><b>Why this is needed at all</b> (confirmed live, {@code javap} against the actually-resolved
 * SAS 7.1.0 jar, same discipline TD-SEC-028's own investigation already used): the default mapper
 * reads claims from the **ID token**, not the access token, then filters them down to an explicit
 * allow-list (`sub`, plus `address`/email/phone/profile claim groups gated by the corresponding
 * requested scope) — any claim outside that list, including a custom one like {@code
 * workspace_role}, is silently dropped even though it's genuinely present on the ID token. There is
 * no supported way to extend that allow-list; replacing the mapper wholesale is SAS's own
 * documented extension point for this exact case.
 *
 * <p>Faithfully reproduces the default's own filtering logic (same claim-group lists, same
 * scope-gating) for every standard claim, so nothing already-conformant regresses — this codebase
 * doesn't populate any of {@code email}/{@code phone_number}/{@code profile}'s own sub-claims
 * today, so that half is currently a no-op, but must stay correct the day it isn't. {@code
 * workspace_id}/{@code workspace_role}/{@code workspace_permissions} are added unconditionally on
 * top, whenever present on the ID token — they have no dedicated OIDC scope of their own (BR-WS
 * doesn't define one), so gating them behind a scope isn't applicable the way it is for the
 * standard claim groups.
 */
public class WorkspaceAwareOidcUserInfoMapper
    implements Function<OidcUserInfoAuthenticationContext, OidcUserInfo> {

  private static final List<String> PHONE_CLAIMS = List.of("phone_number", "phone_number_verified");
  private static final List<String> PROFILE_CLAIMS =
      List.of(
          "name",
          "family_name",
          "given_name",
          "middle_name",
          "nickname",
          "preferred_username",
          "profile",
          "picture",
          "website",
          "gender",
          "birthdate",
          "zoneinfo",
          "locale",
          "updated_at");
  // The email scope and the email claim share a name (OIDC Core 5.4).
  private static final String EMAIL_SCOPE = "email";
  private static final String SUBJECT_CLAIM = "sub";
  private static final String ADDRESS_CLAIM = "address";
  private static final String WORKSPACE_ID_CLAIM = "workspace_id";
  private static final String WORKSPACE_ROLE_CLAIM = "workspace_role";
  private static final String WORKSPACE_PERMISSIONS_CLAIM = "workspace_permissions";

  private final AccountRepository accounts;

  public WorkspaceAwareOidcUserInfoMapper(final AccountRepository accounts) {
    this.accounts = accounts;
  }

  @Override
  public OidcUserInfo apply(final OidcUserInfoAuthenticationContext context) {
    final OAuth2Authorization.Token<OidcIdToken> idTokenAuthorization =
        context.getAuthorization().getToken(OidcIdToken.class);
    final OidcIdToken idToken = idTokenAuthorization.getToken();
    final Map<String, Object> idTokenClaims = idToken.getClaims();
    final Set<String> requestedScopes = context.getAccessToken().getScopes();

    final Set<String> allowedClaimNames = new HashSet<>();
    allowedClaimNames.add(SUBJECT_CLAIM);
    if (requestedScopes.contains(ADDRESS_CLAIM)) {
      allowedClaimNames.add(ADDRESS_CLAIM);
    }
    if (requestedScopes.contains("phone")) {
      allowedClaimNames.addAll(PHONE_CLAIMS);
    }
    if (requestedScopes.contains("profile")) {
      allowedClaimNames.addAll(PROFILE_CLAIMS);
    }

    final Map<String, Object> userInfoClaims = new HashMap<>(idTokenClaims);
    userInfoClaims.keySet().removeIf(claimName -> !allowedClaimNames.contains(claimName));

    if (requestedScopes.contains(EMAIL_SCOPE)) {
      addEmailClaims(context, userInfoClaims);
    }

    // Not scope-gated — BR-WS defines no dedicated OIDC scope for workspace membership, unlike
    // the standard claim groups above.
    if (idTokenClaims.containsKey(WORKSPACE_ID_CLAIM)) {
      userInfoClaims.put(WORKSPACE_ID_CLAIM, idTokenClaims.get(WORKSPACE_ID_CLAIM));
    }
    if (idTokenClaims.containsKey(WORKSPACE_ROLE_CLAIM)) {
      userInfoClaims.put(WORKSPACE_ROLE_CLAIM, idTokenClaims.get(WORKSPACE_ROLE_CLAIM));
    }
    if (idTokenClaims.containsKey(WORKSPACE_PERMISSIONS_CLAIM)) {
      userInfoClaims.put(
          WORKSPACE_PERMISSIONS_CLAIM, idTokenClaims.get(WORKSPACE_PERMISSIONS_CLAIM));
    }

    return new OidcUserInfo(userInfoClaims);
  }

  /**
   * OIDC Core §5.4: with {@code response_type=code} the {@code email} scope is a request for the
   * claims at {@code /userinfo}, not on the ID token. The ID token is what a relying party passes
   * on as proof of the sign-in, so an address placed there travels further than the client asked it
   * to. The claims are read from the Account here, at the moment of the request, so an address the
   * End-User has since changed is the one returned. The verification flag is always sent, {@code
   * false} included, so a relying party can tell "unverified" from "not provided".
   */
  private void addEmailClaims(
      final OidcUserInfoAuthenticationContext context, final Map<String, Object> userInfoClaims) {
    final UUID accountId;
    try {
      accountId = UUID.fromString(context.getAuthorization().getPrincipalName());
    } catch (final IllegalArgumentException _) {
      return;
    }
    accounts
        .findById(new AccountId(accountId))
        .ifPresent(
            account -> {
              userInfoClaims.put(EMAIL_SCOPE, account.email().value());
              userInfoClaims.put("email_verified", account.emailVerifiedAt().isPresent());
            });
  }
}
