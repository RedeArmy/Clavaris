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

  // BR-CLIENT-01: redirect_uris are an exact-match allowlist — no wildcard/partial matching is
  // ever applied at /authorize time. That guarantee only means something if what's stored here is
  // itself a well-formed, absolute URI to begin with; a malformed entry would be un-matchable
  // (and un-auditable) either way, so it's rejected at registration, not discovered later.
  private static List<String> requireValidRedirectUris(final List<String> redirectUris) {
    requireNonEmpty(redirectUris, "redirectUris");
    for (final String redirectUri : redirectUris) {
      if (redirectUri == null || redirectUri.isBlank()) {
        throw new IllegalArgumentException("redirectUris must not contain a blank entry");
      }
      final URI parsed;
      try {
        parsed = URI.create(redirectUri);
      } catch (final IllegalArgumentException e) {
        throw new IllegalArgumentException(
            "redirectUris must contain only well-formed URIs: " + redirectUri, e);
      }
      if (!parsed.isAbsolute()) {
        throw new IllegalArgumentException(
            "redirectUris must contain only absolute URIs: " + redirectUri);
      }
    }
    return List.copyOf(redirectUris);
  }

  // TD-FUT-018: same well-formed/absolute requirement as requireValidRedirectUris above, minus
  // its non-empty requirement — unlike redirectUris (meaningless empty, BR-CLIENT-01 has nothing
  // to match against), an empty post-logout allowlist is the genuinely valid "not configured"
  // state, not an error.
  private static List<String> requireValidAbsoluteUris(final List<String> uris) {
    Objects.requireNonNull(uris, "postLogoutRedirectUris must not be null");
    for (final String uri : uris) {
      if (uri == null || uri.isBlank()) {
        throw new IllegalArgumentException("postLogoutRedirectUris must not contain a blank entry");
      }
      final URI parsed;
      try {
        parsed = URI.create(uri);
      } catch (final IllegalArgumentException e) {
        throw new IllegalArgumentException(
            "postLogoutRedirectUris must contain only well-formed URIs: " + uri, e);
      }
      if (!parsed.isAbsolute()) {
        throw new IllegalArgumentException(
            "postLogoutRedirectUris must contain only absolute URIs: " + uri);
      }
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
