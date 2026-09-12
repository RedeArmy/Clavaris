package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import java.util.List;

/**
 * Web-layer only — unlike {@code PlatformScopes} (enforced at the domain layer by {@code
 * OrganizationClient}/{@code PlatformClient}'s own validation), {@code
 * OAuthClient.allowedGrantTypes} has no domain-level restriction to a fixed vocabulary (see {@code
 * OrganizationRegisteredClientRepository}, which forwards whatever string is stored straight into
 * {@code new AuthorizationGrantType(grant)}). This is purely the checkbox vocabulary the
 * dashboard's own create form offers — the three grant types this codebase's own Spring
 * Authorization Server wiring actually understands functionally (confirmed: {@code
 * RefreshTokenRotationAuthenticationProvider}, {@code WorkspaceRoleClaimsCustomizer}, and every
 * platform/organization client_credentials flow already in this codebase) — not an exhaustive or
 * domain-enforced allowlist.
 *
 * <p>PMD.DataClass: a plain constants holder, same false positive {@code PlatformScopes} already
 * suppresses for an identical shape. PMD.LongVariable: {@code AUTHORIZATION_CODE}/{@code
 * CLIENT_CREDENTIALS} are the exact OAuth2 spec terms, not arbitrarily long — same precedent as
 * {@code PlatformScopes}' own suppression.
 */
@SuppressWarnings({"PMD.DataClass", "PMD.LongVariable"})
public final class OAuthGrantTypeOptions {

  public static final String AUTHORIZATION_CODE = "authorization_code";
  public static final String REFRESH_TOKEN = "refresh_token";
  public static final String CLIENT_CREDENTIALS = "client_credentials";

  public static final List<String> DASHBOARD_OPTIONS =
      List.of(AUTHORIZATION_CODE, REFRESH_TOKEN, CLIENT_CREDENTIALS);

  private OAuthGrantTypeOptions() {
    // Constants only.
  }
}
