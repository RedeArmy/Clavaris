package com.clavaris.clientregistry.application.usecase.registeroauthclient;

import java.util.List;

/**
 * BR-ORG-06 (SDE-III refactor, 2026-09-23, Clerk-parity self-service simplification): the fixed
 * defaults every new {@code OAuthClient} registers with now — both the one auto-provisioned
 * synchronously at Organization-creation time (organization-module's own {@code
 * OAuthClientProvisioner} port, bridged in {@code app}) and any additional client an Organization
 * owner adds later via the dashboard's own "Add client" action. Neither caller lets the end user
 * pick grant types, scopes, or the consent-screen setting — those three are Clavaris's own,
 * consciously chosen, not a per-client decision an Organization owner has to understand OAuth2 well
 * enough to get right. {@code redirectUris}/{@code postLogoutRedirectUris} are deliberately NOT
 * here — the one thing the user configures afterward, via {@code
 * updateoauthclientredirectsettings}.
 */
public final class OAuthClientDefaults {

  /**
   * authorization_code + refresh_token cover the standard hosted-login web/mobile flow;
   * client_credentials additionally lets the consuming application's own backend call its own
   * Organization-scoped resources machine-to-machine, without a redirect URI ever being involved —
   * a real, common combination (confirmed structurally sound: {@code
   * OrganizationRegisteredClientRepository} registers every configured grant unconditionally, and
   * Spring Authorization Server tolerates a client_credentials-capable client that also carries
   * authorization_code/refresh_token fine).
   */
  public static final List<String> GRANT_TYPES =
      List.of("authorization_code", "refresh_token", "client_credentials");

  /**
   * The full {@code OidcScopeCatalog.KNOWN} set — every scope this system's own OIDC/consent/
   * userinfo machinery gives real meaning to. {@code offline_access} pairs coherently with the
   * {@code refresh_token} grant above (a client that can't request offline access has no meaningful
   * use for a refresh token in the first place).
   */
  public static final List<String> SCOPES = List.of("openid", "profile", "email", "offline_access");

  /** ADR-0017: secure-by-default — no exception for the auto-provisioned client. */
  public static final boolean REQUIRE_CONSENT = true;

  private OAuthClientDefaults() {}
}
