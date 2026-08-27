package com.clavaris.app.infrastructure.config;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.oidc.authentication.OidcUserInfoAuthenticationContext;

/**
 * {@code /userinfo} response mapper — replaces SAS's own {@code DefaultOidcUserInfoMapper} so the
 * {@code workspace_id}/{@code workspace_role} claims {@link WorkspaceRoleClaimsCustomizer} adds to
 * the ID token actually reach {@code /userinfo}, not just the token response.
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
 * workspace_id}/ {@code workspace_role} are added unconditionally on top, whenever present on the
 * ID token — they have no dedicated OIDC scope of their own (BR-WS doesn't define one), so gating
 * them behind a scope isn't applicable the way it is for the standard claim groups.
 */
// PMD.LongVariable: WORKSPACE_ID_CLAIM/WORKSPACE_ROLE_CLAIM name exactly what they hold — the
// literal claim key this class writes into the userinfo response, same "abbreviating would only
// make the call site harder to read" precedent PlatformScopes' own class-wide suppression already
// documents. PMD.AtLeastOneConstructor: this class holds no state, only the apply() method below —
// same "intentionally empty" precedent AdminApiSecurityConfig's own identical suppression uses.
@SuppressWarnings({"PMD.LongVariable", "PMD.AtLeastOneConstructor"})
class WorkspaceAwareOidcUserInfoMapper
    implements Function<OidcUserInfoAuthenticationContext, OidcUserInfo> {

  private static final List<String> EMAIL_CLAIMS = List.of("email", "email_verified");
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
  private static final String SUBJECT_CLAIM = "sub";
  private static final String ADDRESS_CLAIM = "address";
  private static final String WORKSPACE_ID_CLAIM = "workspace_id";
  private static final String WORKSPACE_ROLE_CLAIM = "workspace_role";

  // PMD.LawOfDemeter: context.getAuthorization()/getAccessToken() is the standard SAS API shape
  // for this extension point — same "there is no other way to reach it" reasoning as
  // TokenIssuanceEventLogger's own identical suppression.
  @SuppressWarnings("PMD.LawOfDemeter")
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
    if (requestedScopes.contains("email")) {
      allowedClaimNames.addAll(EMAIL_CLAIMS);
    }
    if (requestedScopes.contains("phone")) {
      allowedClaimNames.addAll(PHONE_CLAIMS);
    }
    if (requestedScopes.contains("profile")) {
      allowedClaimNames.addAll(PROFILE_CLAIMS);
    }

    final Map<String, Object> userInfoClaims = new HashMap<>(idTokenClaims);
    userInfoClaims.keySet().removeIf(claimName -> !allowedClaimNames.contains(claimName));

    // Not scope-gated — BR-WS defines no dedicated OIDC scope for workspace membership, unlike
    // the standard claim groups above.
    if (idTokenClaims.containsKey(WORKSPACE_ID_CLAIM)) {
      userInfoClaims.put(WORKSPACE_ID_CLAIM, idTokenClaims.get(WORKSPACE_ID_CLAIM));
    }
    if (idTokenClaims.containsKey(WORKSPACE_ROLE_CLAIM)) {
      userInfoClaims.put(WORKSPACE_ROLE_CLAIM, idTokenClaims.get(WORKSPACE_ROLE_CLAIM));
    }

    return new OidcUserInfo(userInfoClaims);
  }
}
