package com.clavaris.app.infrastructure.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

/**
 * ADR-0016: adds the standard OIDC {@code acr} (Authentication Context Class Reference) and {@code
 * amr} (Authentication Methods References) claims to every ID token the interactive Authorization
 * Code flow issues — ISO/IEC 29115's Entity Authentication Assurance Framework is what OpenID
 * Connect Core itself points to for interpreting {@code acr} (its own §3.1.3.7 example: "the value
 * '0' indicates the End-User authentication did not meet the requirements of ISO/IEC 29115 level
 * 1"), so populating these claims is adopting a standard this project's own protocol already
 * references, not inventing a new one.
 *
 * <p>ADR-0020 (social login) is the "second authentication method" this class's own earlier version
 * flagged as needing a real revisit, not just an extension — this IS that revisit. {@code amr} is
 * now computed from the authenticated principal's own {@link GrantedAuthority} set rather than
 * hardcoded: {@link SpringSecurityAuthenticatedSessionEstablisher#establish} (password) adds no
 * {@code AMR_}-prefixed authority at all, so the fallback below still yields exactly {@code
 * ["pwd"]}, unchanged from before this class existed in its current form; {@link
 * SpringSecurityAuthenticatedSessionEstablisher#establishViaSocialLogin} adds one named after the
 * actual {@code SocialProvider} (e.g. {@code AMR_GOOGLE} → {@code "google"}), which is what a
 * social login's ID token gets instead. {@code acr} stays {@code LOA2_URN} for every path today —
 * v1 has no MFA to justify Level 3, and this codebase does not (and structurally cannot)
 * independently verify whatever assurance level Google/GitHub themselves applied to their own
 * login, so treating a federated login as anything other than "some confidence, single factor" from
 * Clavaris's own point of view would be a claim this codebase can't actually back up.
 *
 * <p>{@code LOA2_URN} is a URN this project mints itself ({@code urn:clavaris:loa:2}), not a
 * registry-assigned one — ISO/IEC 29115 defines the four assurance *levels* but does not itself
 * publish a canonical URN for each; ecosystems that use {@code acr} for this mint their own (Open
 * Banking Brazil's security profile, confirmed live via its own published spec, uses {@code
 * urn:brasil:openbanking:loa2}/{@code loa3} for exactly this purpose) — this project follows that
 * same, real precedent rather than inventing an unprecedented pattern. {@code amr} values follow
 * the same "mint our own where no registry value fits" posture: IANA's own RFC 8176 registry has no
 * entry for a federated/OAuth2 login, so this project uses the provider's own name — the same
 * real-world convention several production IdPs already use for exactly this case.
 *
 * <p>Only touches ID tokens — {@code acr}/{@code amr} are OIDC ID Token claims (OpenID Connect Core
 * §2), not access token claims; the platform tier's own {@code client_credentials}-only chain
 * (`PlatformAuthorizationServerConfig`) never issues an ID token at all (no end-user principal
 * exists in that grant), so this customizer is wired only into the Organization tier's chain.
 *
 * <p>Deliberately not a {@code @Component}: {@link TokenIssuanceEventLogger} is already the sole
 * Spring-managed {@code OAuth2TokenCustomizer<JwtEncodingContext>} bean this codebase wires by that
 * interface type (`OrganizationAuthorizationServerConfig`'s own {@code tokenIssuanceLogger}
 * parameter) — adding a second bean of the same interface type would make that by-type injection
 * ambiguous. Constructed directly, once, alongside the other locally-scoped collaborators already
 * built with {@code new} in that same method ({@code TenantAccountOnlySecurityContextFilter},
 * {@code OrganizationLoginRedirectEntryPoint}), and composed with {@code tokenIssuanceLogger} into
 * one customizer lambda — the same one-{@code JwtGenerator}-customizer-slot constraint that already
 * shapes how this codebase adds a second concern to token issuance.
 */
// PMD.LongVariable: AMR_AUTHORITY_PREFIX names exactly what it is — see this class's own AMR
// rationale above. PMD.LawOfDemeter: context.getClaims()/getPrincipal()/principal.getAuthorities()
// are all the standard SAS/Spring Security API shape for reading a token-issuance context and its
// principal — there is no other way to reach any of them, same reasoning
// AntiAbuseRateLimitingFilter's own response.getWriter() suppression already documents.
@SuppressWarnings({"PMD.LongVariable", "PMD.LawOfDemeter"})
class AuthenticationContextClaimsCustomizer implements OAuth2TokenCustomizer<JwtEncodingContext> {

  private static final OAuth2TokenType ID_TOKEN_TYPE =
      new OAuth2TokenType(OidcParameterNames.ID_TOKEN);

  // ISO/IEC 29115 Level 2 ("some confidence" — single-factor, knowledge-based authentication) —
  // see this class's own Javadoc for why this project mints its own URN rather than using a
  // registry-assigned one.
  private static final String LOA2_URN = "urn:clavaris:loa:2";

  // Matches SpringSecurityAuthenticatedSessionEstablisher.establishViaSocialLogin's own
  // "AMR_" + provider.name() authority — see this class's own Javadoc for the full amr rationale.
  private static final String AMR_AUTHORITY_PREFIX = "AMR_";
  private static final String DEFAULT_AMR_VALUE = "pwd";

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  /* package */ AuthenticationContextClaimsCustomizer() {
    // Intentionally empty — this class holds no state, only the customize() method below.
  }

  @Override
  public void customize(final JwtEncodingContext context) {
    if (!ID_TOKEN_TYPE.equals(context.getTokenType())) {
      return;
    }
    // TD-SEC-028: a plain ArrayList, not List.of(...) (java.util.ImmutableCollections$List12 at
    // runtime) — JdbcOAuth2AuthorizationService's own Jackson3 PolymorphicTypeValidator rejects
    // that type the moment anything actually deserializes a persisted OAuth2Authorization's claims
    // back out; a plain ArrayList is on that validator's own allow-list.
    context.getClaims().claim("acr", LOA2_URN).claim("amr", new ArrayList<>(resolveAmr(context)));
  }

  private List<String> resolveAmr(final JwtEncodingContext context) {
    final Authentication principal = context.getPrincipal();
    final List<String> amr = new ArrayList<>();
    for (final GrantedAuthority authority : principal.getAuthorities()) {
      final String name = authority.getAuthority();
      if (name != null && name.startsWith(AMR_AUTHORITY_PREFIX)) {
        amr.add(name.substring(AMR_AUTHORITY_PREFIX.length()).toLowerCase(Locale.ROOT));
      }
    }
    if (amr.isEmpty()) {
      // No AMR_-prefixed authority present — the password path
      // (SpringSecurityAuthenticatedSessionEstablisher.establish()) never adds one, since "pwd"
      // was already this codebase's own unconditional default before social login existed; this
      // branch keeps that exact behaviour unchanged.
      amr.add(DEFAULT_AMR_VALUE);
    }
    return amr;
  }
}
