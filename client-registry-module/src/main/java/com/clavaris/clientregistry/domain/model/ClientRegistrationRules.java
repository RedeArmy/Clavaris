package com.clavaris.clientregistry.domain.model;

import java.net.URI;
import java.util.List;
import java.util.Objects;

/**
 * The validation rules {@link OAuthClient} applies to what a consuming application registers:
 * redirect URIs (BR-CLIENT-01), grant types and scopes. Pure functions over their input, extracted
 * from the aggregate so it holds state and transitions while these rules stay testable on their own
 * and shared ({@link RedirectPolicy} reuses the URI check) instead of duplicated.
 */
final class ClientRegistrationRules {

  /**
   * RFC 8252 §7.3: the only {@code http} scheme exception — loopback traffic never leaves the
   * device, so there's nothing on the network path to intercept, unlike a plaintext {@code http://}
   * redirect to an arbitrary host. {@code PMD.AvoidUsingHardCodedIP}: this is a validation literal
   * being compared against, never a real network endpoint this class connects to — the pattern that
   * rule exists to catch (a hardcoded address baked into outbound connection logic) doesn't apply
   * here.
   */
  @SuppressWarnings("PMD.AvoidUsingHardCodedIP")
  private static final String LOOPBACK_IP = "127.0.0.1";

  private static final String LOCALHOST = "localhost";

  private ClientRegistrationRules() {}

  /* package */ static List<String> requireNonEmpty(
      final List<String> values, final String fieldName) {
    if (values == null || values.isEmpty()) {
      throw new IllegalArgumentException(fieldName + " must not be empty");
    }
    return values;
  }

  // Live UX request, 2026-09-24 (Configuration card made editable): unlike allowedScopes,
  // allowedGrantTypes genuinely IS a closed set in this system — see OAuthGrantTypeCatalog's own
  // Javadoc for why. This is the one real guard against a value
  // OrganizationRegisteredClientRepository
  // would otherwise forward blindly into Spring Authorization Server.
  /* package */ static List<String> requireKnownGrantTypes(final List<String> allowedGrantTypes) {
    for (final String grantType : allowedGrantTypes) {
      if (!OAuthGrantTypeCatalog.KNOWN.contains(grantType)) {
        throw new IllegalArgumentException(
            "allowedGrantTypes must only contain known grant types "
                + OAuthGrantTypeCatalog.KNOWN
                + ": "
                + grantType);
      }
    }
    return List.copyOf(allowedGrantTypes);
  }

  // TD-ARCH-004: rejects the one half of "allowedScopes is free text nothing validates" that's
  // actually enforceable here — a scope from the reserved platform:* namespace (PlatformScopes)
  // has no business on a tenant-facing OAuthClient at all, since it would be forwarded verbatim
  // into SAS's own RegisteredClient.scope(...) (OrganizationRegisteredClientRepository) alongside
  // this client's real ones. Deliberately does NOT restrict to OidcScopeCatalog.KNOWN the way
  // PlatformClient/OrganizationClient restrict to PlatformScopes.BOOTSTRAP_DEFAULT: unlike those
  // two (a small, fixed, Clavaris-owned admin-API vocabulary), an OAuthClient using the
  // client_credentials grant legitimately needs arbitrary, consumer-defined API scopes Clavaris
  // has no way to know in advance (confirmed live — SigningKeyRotationIntegrationTest/
  // OrganizationOidcIssuerIntegrationTest both register a real OAuthClient with a custom
  // "test.read" scope for exactly this reason) — OidcScopeCatalog.KNOWN documents which scopes
  // this system's own login/consent/userinfo machinery actually understands, not an exhaustive
  // allowlist of every scope an OAuthClient may ever hold. An empty list is valid (a client that
  // only ever completes client_credentials-shaped flows genuinely needs none).
  /* package */ static List<String> requireValidScopes(final List<String> allowedScopes) {
    Objects.requireNonNull(allowedScopes, "allowedScopes must not be null");
    for (final String scope : allowedScopes) {
      if (scope.startsWith(PlatformScopes.NAMESPACE_PREFIX)) {
        throw new IllegalArgumentException(
            "allowedScopes must not contain a reserved platform:* scope: " + scope);
      }
    }
    return List.copyOf(allowedScopes);
  }

  private static boolean isInsecureHttp(final URI uri) {
    return "http".equalsIgnoreCase(uri.getScheme())
        && !LOCALHOST.equalsIgnoreCase(uri.getHost())
        && !LOOPBACK_IP.equals(uri.getHost());
  }

  /**
   * SDE-III review, 2026-09-03 — real bug found and closed: neither caller below used to restrict
   * scheme at all, only "well-formed and absolute" — a client could register a plaintext {@code
   * http://} redirect URI against an arbitrary network-reachable host, exposing the authorization
   * code (or, for {@code postLogoutRedirectUris}, the {@code id_token_hint}-bearing RP-initiated
   * logout redirect) to interception by anything on the network path. Extracted as one shared
   * per-entry validator, not duplicated per caller ({@code PMD.CyclomaticComplexity} was already at
   * this codebase's own threshold on both callers before this fix even landed) — permits {@code
   * http} against {@code localhost}/{@code 127.0.0.1} (RFC 8252 §7.3 — native-app loopback
   * redirects are exempt everywhere else in the OAuth ecosystem for exactly this reason: loopback
   * traffic never leaves the device, so there's nothing to intercept), and any non-http(s) scheme
   * untouched (a native app's own custom scheme, e.g. {@code com.example.app://callback} — this
   * class's own Javadoc already scopes "web + mobile" clients as in-scope, and a custom scheme was
   * never interceptable over the network to begin with, unlike plaintext HTTP).
   *
   * <p>Four distinct rules (blank, malformed, relative, insecure scheme), each with its own error
   * message; parsing is split out so the check stays under PMD's complexity threshold without a
   * suppression.
   *
   * <p>Package-private: {@link RedirectPolicy} (same package) reuses this exact validator for its
   * own configured URLs — a second, independently-drifting copy of these four rules would be a real
   * duplication risk.
   */
  /* package */ static void requireWellFormedAbsoluteSecureUri(
      final String uri, final String fieldName) {
    final URI parsed = parseWellFormed(uri, fieldName);
    if (!parsed.isAbsolute()) {
      throw new IllegalArgumentException(fieldName + " must contain only absolute URIs: " + uri);
    }
    if (isInsecureHttp(parsed)) {
      throw new IllegalArgumentException(
          fieldName + " must use https, or http only against localhost/127.0.0.1: " + uri);
    }
  }

  private static URI parseWellFormed(final String uri, final String fieldName) {
    if (uri == null || uri.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not contain a blank entry");
    }
    try {
      return URI.create(uri);
    } catch (final IllegalArgumentException e) {
      throw new IllegalArgumentException(
          fieldName + " must contain only well-formed URIs: " + uri, e);
    }
  }

  // BR-CLIENT-01: redirect_uris are an exact-match allowlist — no wildcard/partial matching is
  // ever applied at /authorize time. That guarantee only means something if what's stored here is
  // itself a well-formed, absolute URI to begin with; a malformed entry would be un-matchable
  // (and un-auditable) either way, so it's rejected at registration, not discovered later.
  //
  // SDE-III refactor, 2026-09-23: shares one validator with postLogoutRedirectUris now that both
  // fields allow an empty list (redirectUris' own comment in the constructor explains why) — the
  // two were already identical except for redirectUris' now-removed non-empty requirement. Also
  // deliberately unbounded on both (Clerk-parity check, live-verified against Clerk's own OAuth
  // Applications "Redirect URIs" field, which is itself a multi-entry list, not a single value) —
  // a consuming application legitimately needing web+native, or a canary/staging URL alongside
  // production, on the same client is a real, common case, not one this system should foreclose.
  /* package */ static List<String> requireValidUris(
      final List<String> uris, final String fieldName) {
    Objects.requireNonNull(uris, fieldName + " must not be null");
    for (final String uri : uris) {
      requireWellFormedAbsoluteSecureUri(uri, fieldName);
    }
    return List.copyOf(uris);
  }
}
