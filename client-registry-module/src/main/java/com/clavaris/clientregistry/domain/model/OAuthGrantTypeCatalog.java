package com.clavaris.clientregistry.domain.model;

import java.util.List;

/**
 * Live UX request, 2026-09-24 (Configuration card made editable, reversing BR-ORG-06's original
 * "creation-time-only" rule): unlike {@link OidcScopeCatalog#KNOWN}, this genuinely IS an
 * exhaustive allowlist, not just a reference — these three are the only grant types this system's
 * Spring Authorization Server wiring is ever exercised against ({@code
 * OrganizationRegisteredClientRepository#toRegisteredClient} accepts any string blindly via {@code
 * new AuthorizationGrantType(grant)}, so this class, and {@link OAuthClient}'s own constructor
 * validation against it, is the only real guard against an owner typing/selecting something SAS was
 * never proven to support here). Confirmed live before this class existed: every test and
 * production code path in this repository only ever constructs an {@code allowedGrantTypes} list
 * from a subset of these three values.
 */
// PMD.DataClass: deliberately nothing but a namespaced constants holder plus the one derived list
// — same rationale OidcScopeCatalog's own identical suppression documents. LongVariable:
// AUTHORIZATION_CODE/CLIENT_CREDENTIALS are the exact OAuth2 spec grant type identifiers, not
// arbitrarily long — same precedent PlatformScopes' own identical suppression establishes.
@SuppressWarnings({"PMD.DataClass", "PMD.LongVariable"})
public final class OAuthGrantTypeCatalog {

  /** Standard hosted-login web/mobile flow — the one every interactive OAuthClient needs. */
  public static final String AUTHORIZATION_CODE = "authorization_code";

  /**
   * Lets a session outlive one access token's expiry — pairs with {@link
   * OidcScopeCatalog#OFFLINE_ACCESS}.
   */
  public static final String REFRESH_TOKEN = "refresh_token";

  /**
   * Lets the consuming application's own backend call its own Organization-scoped resources
   * machine-to-machine, without a redirect URI ever being involved.
   */
  public static final String CLIENT_CREDENTIALS = "client_credentials";

  /** Every grant type this system's own Spring Authorization Server wiring is proven to support. */
  public static final List<String> KNOWN =
      List.of(AUTHORIZATION_CODE, REFRESH_TOKEN, CLIENT_CREDENTIALS);

  private OAuthGrantTypeCatalog() {
    // Constants holder — no instances.
  }
}
