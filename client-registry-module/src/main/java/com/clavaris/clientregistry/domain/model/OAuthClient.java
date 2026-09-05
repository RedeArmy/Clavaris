package com.clavaris.clientregistry.domain.model;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * ADR-0010, BR-ORG-02: a consuming application's protocol registration, scoped to exactly one
 * {@code Organization} — {@code organizationId} is an opaque {@link UUID}, not identity-module's
 * own {@code OrganizationId} value type, for the same module-independence reason {@code
 * SigningKeyProvisioner} stays primitive-typed (the hexagonal dependency rule applied at the
 * module-graph level). One Organization may register several {@code OAuthClient}s (web + mobile for
 * the same system), all sharing that Organization's isolated account pool.
 *
 * <p>Deliberately a separate class from {@code PlatformClient} — belongs to exactly one
 * Organization, never none (data-model.md §2).
 *
 * <p>PMD's DataClass/AvoidFieldNameMatchingMethodName/ShortVariable/ShortMethodName rules flag this
 * class for the same reason {@code PlatformClient} suppresses them — the deliberate record-style
 * accessor convention used throughout this codebase's value objects. TooManyMethods is the same
 * shape of false positive: eight one-line accessors plus two rehydration factories is what a value
 * object with this many fields looks like, not a sign this class does too much. LongVariable:
 * {@code postLogoutRedirectUris} is the exact OIDC spec term (post_logout_redirect_uris), not
 * arbitrarily long — same precedent as {@code PlatformScopes}' own identical suppression.
 */
@SuppressWarnings({
  "PMD.DataClass",
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.ShortVariable",
  "PMD.ShortMethodName",
  "PMD.TooManyMethods",
  "PMD.LongVariable"
})
public final class OAuthClient {

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

  private final UUID id;
  private final UUID organizationId;
  private final String clientId;
  private final String clientSecretHash;
  private final List<String> redirectUris;
  private final List<String> allowedGrantTypes;
  private final List<String> allowedScopes;
  private final boolean requireConsent;
  private final List<String> postLogoutRedirectUris;
  private final Instant createdAt;

  // One parameter per persisted column — same rationale as this class's own TooManyMethods
  // suppression above: a rehydration factory for a 10-column aggregate takes 10 parameters, not a
  // sign this constructor does too much. Introducing a synthetic parameter-object purely to dodge
  // the threshold would add indirection without removing any real complexity.
  @SuppressWarnings("java:S107")
  private OAuthClient(
      final UUID id,
      final UUID organizationId,
      final String clientId,
      final String clientSecretHash,
      final List<String> redirectUris,
      final List<String> allowedGrantTypes,
      final List<String> allowedScopes,
      final boolean requireConsent,
      final List<String> postLogoutRedirectUris,
      final Instant createdAt) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.organizationId = Objects.requireNonNull(organizationId, "organizationId must not be null");
    this.clientId = requireNonBlank(clientId, "clientId");
    this.clientSecretHash = requireNonBlank(clientSecretHash, "clientSecretHash");
    this.redirectUris = requireValidRedirectUris(redirectUris);
    this.allowedGrantTypes = List.copyOf(requireNonEmpty(allowedGrantTypes, "allowedGrantTypes"));
    this.allowedScopes =
        List.copyOf(Objects.requireNonNull(allowedScopes, "allowedScopes must not be null"));
    this.requireConsent = requireConsent;
    // TD-FUT-018: genuinely optional, unlike redirectUris — a client with no post-logout redirect
    // configured simply gets SAS's own bare default (redirect to {clavarisBaseUrl}/), same
    // behavior as before this field existed. Still validated the same way (well-formed, absolute)
    // when present: RFC-shaped junk here would be un-matchable at RP-Initiated Logout time either
    // way, same reasoning requireValidRedirectUris already applies to redirectUris.
    this.postLogoutRedirectUris = requireValidAbsoluteUris(postLogoutRedirectUris);
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
  }

  /**
   * @param clientSecretHash the already-hashed value — this factory never sees or accepts a raw
   *     secret; hashing happens at the port boundary, same discipline as {@code PlatformClient}.
   * @param requireConsent TD-SEC-026: whether SAS must show the end user a consent screen before
   *     issuing an authorization code to this client. No implicit default at this layer — the web
   *     adapter resolves an absent request field to {@code true} (secure by default, ADR-0017); the
   *     domain factory itself takes an explicit value so a future caller can't add one without
   *     consciously deciding it.
   */
  @SuppressWarnings("java:S107")
  public static OAuthClient register(
      final UUID organizationId,
      final String clientId,
      final String clientSecretHash,
      final List<String> redirectUris,
      final List<String> allowedGrantTypes,
      final List<String> allowedScopes,
      final boolean requireConsent,
      final List<String> postLogoutRedirectUris) {
    return new OAuthClient(
        UUID.randomUUID(),
        organizationId,
        clientId,
        clientSecretHash,
        redirectUris,
        allowedGrantTypes,
        allowedScopes,
        requireConsent,
        postLogoutRedirectUris,
        Instant.now());
  }

  /**
   * Rehydrates an existing row — preserves the real persisted {@code id}, same discipline as {@code
   * PlatformClient#reconstitute}. One parameter per persisted column, same rationale as the private
   * constructor's own identical suppression above — a synthetic parameter object here would add
   * indirection without removing any real complexity.
   */
  @SuppressWarnings({"java:S107", "PMD.ExcessiveParameterList"})
  public static OAuthClient reconstitute(
      final UUID id,
      final UUID organizationId,
      final String clientId,
      final String clientSecretHash,
      final List<String> redirectUris,
      final List<String> allowedGrantTypes,
      final List<String> allowedScopes,
      final boolean requireConsent,
      final List<String> postLogoutRedirectUris,
      final Instant createdAt) {
    return new OAuthClient(
        id,
        organizationId,
        clientId,
        clientSecretHash,
        redirectUris,
        allowedGrantTypes,
        allowedScopes,
        requireConsent,
        postLogoutRedirectUris,
        createdAt);
  }

  private static String requireNonBlank(final String value, final String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
    return value;
  }

  private static List<String> requireNonEmpty(final List<String> values, final String fieldName) {
    if (values == null || values.isEmpty()) {
      throw new IllegalArgumentException(fieldName + " must not be empty");
    }
    return values;
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
   * <p>{@code PMD.CyclomaticComplexity}: four genuinely distinct validation rules (blank,
   * malformed, relative, insecure-scheme), each its own branch with its own distinct error message
   * — already the extraction that brought both callers back under this threshold; splitting this
   * single per-entry validator further would fragment one cohesive check into several without
   * removing any real complexity, not reduce it.
   *
   * <p>Package-private, not {@code private}: {@link RedirectPolicy} (same package) reuses this
   * exact validator for its own configured URLs — a second, independently-drifting copy of these
   * four rules would be a real duplication risk, not a reason to keep this class-private.
   */
  @SuppressWarnings("PMD.CyclomaticComplexity")
  /* package */ static void requireWellFormedAbsoluteSecureUri(
      final String uri, final String fieldName) {
    if (uri == null || uri.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not contain a blank entry");
    }
    final URI parsed;
    try {
      parsed = URI.create(uri);
    } catch (final IllegalArgumentException e) {
      throw new IllegalArgumentException(
          fieldName + " must contain only well-formed URIs: " + uri, e);
    }
    if (!parsed.isAbsolute()) {
      throw new IllegalArgumentException(fieldName + " must contain only absolute URIs: " + uri);
    }
    if (isInsecureHttp(parsed)) {
      throw new IllegalArgumentException(
          fieldName + " must use https, or http only against localhost/127.0.0.1: " + uri);
    }
  }

  // BR-CLIENT-01: redirect_uris are an exact-match allowlist — no wildcard/partial matching is
  // ever applied at /authorize time. That guarantee only means something if what's stored here is
  // itself a well-formed, absolute URI to begin with; a malformed entry would be un-matchable
  // (and un-auditable) either way, so it's rejected at registration, not discovered later.
  private static List<String> requireValidRedirectUris(final List<String> redirectUris) {
    requireNonEmpty(redirectUris, "redirectUris");
    for (final String redirectUri : redirectUris) {
      requireWellFormedAbsoluteSecureUri(redirectUri, "redirectUris");
    }
    return List.copyOf(redirectUris);
  }

  // TD-FUT-018: same well-formed/absolute/secure requirement as requireValidRedirectUris above,
  // minus its non-empty requirement — unlike redirectUris (meaningless empty, BR-CLIENT-01 has
  // nothing to match against), an empty post-logout allowlist is the genuinely valid "not
  // configured" state, not an error.
  private static List<String> requireValidAbsoluteUris(final List<String> uris) {
    Objects.requireNonNull(uris, "postLogoutRedirectUris must not be null");
    for (final String uri : uris) {
      requireWellFormedAbsoluteSecureUri(uri, "postLogoutRedirectUris");
    }
    return List.copyOf(uris);
  }

  public UUID id() {
    return id;
  }

  public UUID organizationId() {
    return organizationId;
  }

  public String clientId() {
    return clientId;
  }

  public String clientSecretHash() {
    return clientSecretHash;
  }

  public List<String> redirectUris() {
    return redirectUris;
  }

  public List<String> allowedGrantTypes() {
    return allowedGrantTypes;
  }

  public List<String> allowedScopes() {
    return allowedScopes;
  }

  public boolean requireConsent() {
    return requireConsent;
  }

  public List<String> postLogoutRedirectUris() {
    return postLogoutRedirectUris;
  }

  public Instant createdAt() {
    return createdAt;
  }
}
