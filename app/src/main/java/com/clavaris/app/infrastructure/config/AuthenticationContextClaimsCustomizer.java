package com.clavaris.app.infrastructure.config;

import java.util.ArrayList;
import java.util.List;
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
 * <p>Today this is deliberately simple, not a general-purpose mapping engine: {@code
 * AuthenticateWithPasswordUseCase} is the only way a real end-user session on this codebase's own
 * interactive flow is ever established (BR-ID-02) — single-factor, knowledge-based authentication,
 * which ISO/IEC 29115's own four-level framework places at **Level 2** ("some confidence," the
 * level password-only authentication satisfies — Level 1 is "little or no confidence," Level 3+
 * requires multi-factor). So every ID token this class touches gets exactly the same two claims
 * today: {@code acr=LOA2_URN}, {@code amr=["pwd"]}. There is no real branching logic yet because
 * there is only one real authentication method yet.
 *
 * <p>{@code LOA2_URN} is a URN this project mints itself ({@code urn:clavaris:loa:2}), not a
 * registry-assigned one — ISO/IEC 29115 defines the four assurance *levels* but does not itself
 * publish a canonical URN for each; ecosystems that use {@code acr} for this mint their own (Open
 * Banking Brazil's security profile, confirmed live via its own published spec, uses {@code
 * urn:brasil:openbanking:loa2}/{@code loa3} for exactly this purpose) — this project follows that
 * same, real precedent rather than inventing an unprecedented pattern.
 *
 * <p>**Must be revisited, not just extended, the day a second authentication method ships** — MFA
 * (backlog, `prd-mvp.md`) would genuinely justify Level 3 and a richer {@code amr} (e.g. {@code
 * ["pwd", "otp"]}); social login (`TD-FUT-008`, v1 scope) authenticates via a third party this
 * project doesn't control the assurance level of, which is its own design question, not answered
 * here. Hardcoding Level 2/{@code ["pwd"]} today is accurate for what this codebase actually does,
 * not a placeholder pretending to be more general than it is.
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
class AuthenticationContextClaimsCustomizer implements OAuth2TokenCustomizer<JwtEncodingContext> {

  private static final OAuth2TokenType ID_TOKEN_TYPE =
      new OAuth2TokenType(OidcParameterNames.ID_TOKEN);

  // ISO/IEC 29115 Level 2 ("some confidence" — single-factor, knowledge-based authentication) —
  // see this class's own Javadoc for why this project mints its own URN rather than using a
  // registry-assigned one.
  private static final String LOA2_URN = "urn:clavaris:loa:2";

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  /* package */ AuthenticationContextClaimsCustomizer() {
    // Intentionally empty — this class holds no state, only the customize() method below.
  }

  // PMD.LawOfDemeter: context.getClaims() is the standard SAS API shape for customizing a token's
  // claim set from within an OAuth2TokenCustomizer — same "there is no other way to reach it"
  // reasoning as AntiAbuseRateLimitingFilter's own response.getWriter() suppression.
  @SuppressWarnings("PMD.LawOfDemeter")
  @Override
  public void customize(final JwtEncodingContext context) {
    if (!ID_TOKEN_TYPE.equals(context.getTokenType())) {
      return;
    }
    // TD-SEC-028: List.of(...) (java.util.ImmutableCollections$List12 at runtime) is rejected by
    // JdbcOAuth2AuthorizationService's own Jackson3 PolymorphicTypeValidator the moment anything
    // actually deserializes a persisted OAuth2Authorization's claims back out — never caught before
    // because nothing had ever read an ID token's stored claims back until /userinfo (TD-SEC-028)
    // was made to actually work. A plain ArrayList is on that validator's own allow-list.
    context.getClaims().claim("acr", LOA2_URN).claim("amr", new ArrayList<>(List.of("pwd")));
  }
}
