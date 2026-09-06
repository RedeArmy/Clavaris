package com.clavaris.clientregistry.domain.model;

import java.util.List;

/**
 * TD-ARCH-004: documents every OIDC-identity scope this system's own login/consent/{@code
 * /userinfo} machinery actually understands ({@code WorkspaceAwareOidcUserInfoMapper}, {@code
 * RefreshTokenRotationAuthenticationProvider}, both in {@code app}) — the reference an operator (or
 * a future self-service client-registration UI, TD-ARCH-004's own named trigger) reads to know
 * which scopes actually do something in Clavaris's own OIDC flow, as opposed to an arbitrary,
 * consumer-defined {@code client_credentials} API scope Clavaris has no opinion on.
 *
 * <p>Deliberately NOT an exhaustive allowlist {@link OAuthClient}'s own constructor enforces —
 * confirmed live that a real OAuthClient legitimately needs scopes outside this list ( {@code
 * SigningKeyRotationIntegrationTest}/{@code OrganizationOidcIssuerIntegrationTest} both register
 * one with a custom {@code "test.read"} scope for their own {@code client_credentials} grant). What
 * {@link OAuthClient} actually validates every {@code allowedScopes} entry against is narrower and
 * enforceable: it must not fall in the reserved {@link PlatformScopes#NAMESPACE_PREFIX} namespace —
 * see that check's own Javadoc for why a full catalog restriction would be wrong here, unlike
 * {@link PlatformClient}/{@code OrganizationClient}, which really do have a small, fixed,
 * Clavaris-owned vocabulary ({@link PlatformScopes#BOOTSTRAP_DEFAULT}) to validate against.
 *
 * <p>Plain string literals, not {@code org.springframework.security.oauth2.core.oidc.OidcScopes}'s
 * own constants — {@code domain/} depends on nothing outside itself (CLAUDE.md §7.2's dependency
 * rule, enforced live by {@code HexagonalArchitectureTest}); a Spring Security import here, even a
 * harmless constants class, would violate that same rule {@link PlatformScopes} already respects
 * for its own scope strings.
 */
// PMD.DataClass: deliberately nothing but a namespaced constants holder plus the one derived list
// — same rationale PlatformScopes' own identical suppression documents.
@SuppressWarnings("PMD.DataClass")
public final class OidcScopeCatalog {

  /** OIDC Core §3.1.2.1 — required on every authorization request this system honors at all. */
  public static final String OPENID = "openid";

  /** OIDC Core §5.4 — the standard claims `UserInfoResponse`/the ID token expose under it. */
  public static final String PROFILE = "profile";

  /** OIDC Core §5.4 — gates `email`/`email_verified` on the ID token and `/userinfo` response. */
  public static final String EMAIL = "email";

  /**
   * Not an OIDC Core scope — the de facto cross-implementation convention (Google, Microsoft,
   * Auth0, and this codebase's own {@code RefreshTokenRotationAuthenticationProvider}) for "this
   * client may hold a refresh token," requested alongside {@code allowedGrantTypes} containing
   * {@code refresh_token}.
   */
  public static final String OFFLINE_ACCESS = "offline_access";

  /**
   * Every scope this system's own OIDC flow gives real meaning to — reference only, not an
   * exhaustive allowlist; see this class's own Javadoc for why.
   */
  public static final List<String> KNOWN = List.of(OPENID, PROFILE, EMAIL, OFFLINE_ACCESS);

  private OidcScopeCatalog() {
    // Constants holder — no instances.
  }
}
