package com.clavaris.organization.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * ADR-0022 (amending ADR-0020 Decision 4): a PRODUCTION Organization's own Google/GitHub OAuth app
 * credentials, opted into on top of the existing {@code Organization.socialLoginEnabled}/{@code
 * allowedSocialProviders} gate (ADR-0020 Decision 3) — bringing your own credentials never bypasses
 * that gate, it only changes which app's credentials are used once social login is already allowed.
 *
 * <p>Absence of a row for a given {@code (organizationId, provider)} pair is the normal state for
 * every Organization that hasn't opted in — same "absence = use the shared default" shape {@link
 * RateLimitPolicy}'s own Javadoc already establishes for this module's other optional, later-set
 * aggregate, not {@code SigningKeyProvisioner}'s "mandatory, provisioned atomically at creation"
 * shape (this is opt-in, not every Organization needs one).
 *
 * <p>{@code clientSecretEncrypted} is reversible ciphertext (AES-256-GCM, {@code
 * OrganizationSocialCredentialCipher}), never a one-way hash — Clavaris must present this secret
 * outbound to Google/GitHub on every token exchange, the opposite shape from {@code
 * OAuthClient.clientSecretHash}. {@code clientId} is plaintext — not sensitive, Google's/GitHub's
 * own client id is not a secret.
 *
 * <p>Same record-style-accessor PMD suppressions as {@link Organization}/{@link RateLimitPolicy},
 * same rationale. {@code PMD.LongVariable} on {@code clientSecretEncrypted}: names exactly what it
 * is — a shortened identifier would only make this field harder to correlate with its own DB
 * column. {@code PMD.DataClass}/{@code PMD.TooManyMethods}: a value object whose method count grows
 * with its field count, not organic complexity — same reasoning {@link Organization}'s own
 * identical suppression already documents.
 */
@SuppressWarnings({
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.ShortVariable",
  "PMD.ShortMethodName",
  "PMD.LongVariable",
  "PMD.DataClass",
  "PMD.TooManyMethods"
})
public final class OrganizationSocialCredential {

  private final UUID id;
  private final UUID organizationId;
  private final SocialProvider provider;
  private final String clientId;
  private final String clientSecretEncrypted;
  private final Instant createdAt;
  private final Instant updatedAt;

  @SuppressWarnings("java:S107") // one parameter per persisted column, same rationale as every
  // other rehydration-shaped constructor in this codebase (e.g. RateLimitPolicy's own identical
  // shape).
  private OrganizationSocialCredential(
      final UUID id,
      final UUID organizationId,
      final SocialProvider provider,
      final String clientId,
      final String clientSecretEncrypted,
      final Instant createdAt,
      final Instant updatedAt) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.organizationId = Objects.requireNonNull(organizationId, "organizationId must not be null");
    this.provider = Objects.requireNonNull(provider, "provider must not be null");
    this.clientId = requireNonBlank(clientId, "clientId");
    this.clientSecretEncrypted = requireNonBlank(clientSecretEncrypted, "clientSecretEncrypted");
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
  }

  /** A brand-new credential for a provider this Organization has never brought its own app for. */
  public static OrganizationSocialCredential define(
      final UUID organizationId,
      final SocialProvider provider,
      final String clientId,
      final String clientSecretEncrypted) {
    final Instant now = Instant.now();
    return new OrganizationSocialCredential(
        UUID.randomUUID(), organizationId, provider, clientId, clientSecretEncrypted, now, now);
  }

  /**
   * A real row already exists for this {@code (organizationId, provider)} pair — replaces it in
   * place, keeping the original {@code id}/{@code createdAt} (same "update in place, never a second
   * row" convention {@link RateLimitPolicy#withRequestsPerMinute} already establishes) and stamping
   * a fresh {@code updatedAt}.
   */
  public OrganizationSocialCredential withCredential(
      final String clientId, final String clientSecretEncrypted) {
    return new OrganizationSocialCredential(
        id, organizationId, provider, clientId, clientSecretEncrypted, createdAt, Instant.now());
  }

  public static OrganizationSocialCredential reconstitute(
      final UUID id,
      final UUID organizationId,
      final SocialProvider provider,
      final String clientId,
      final String clientSecretEncrypted,
      final Instant createdAt,
      final Instant updatedAt) {
    return new OrganizationSocialCredential(
        id, organizationId, provider, clientId, clientSecretEncrypted, createdAt, updatedAt);
  }

  private static String requireNonBlank(final String value, final String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
    return value;
  }

  public UUID id() {
    return id;
  }

  public UUID organizationId() {
    return organizationId;
  }

  public SocialProvider provider() {
    return provider;
  }

  public String clientId() {
    return clientId;
  }

  public String clientSecretEncrypted() {
    return clientSecretEncrypted;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant updatedAt() {
    return updatedAt;
  }
}
