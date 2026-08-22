package com.clavaris.identity.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * BR-ID-01's hash-not-plaintext principle, applied to {@link PlatformAccount} — structurally
 * separate from {@link PasswordCredential} (never a shared type keyed by a generic id) for the same
 * reason {@link PlatformAccountId} is its own type: platform-tier and tenant-tier identities are
 * deliberately never mixed, even where the shape is identical.
 *
 * <p>Same record-style-accessor rationale as {@link PasswordCredential} for the PMD suppressions
 * below.
 */
@SuppressWarnings({
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.ShortVariable",
  "PMD.ShortMethodName"
})
public final class PlatformPasswordCredential {

  private final UUID id;
  private final PlatformAccountId platformAccountId;
  private final String passwordHash;
  private final Instant updatedAt;

  private PlatformPasswordCredential(
      final UUID id,
      final PlatformAccountId platformAccountId,
      final String passwordHash,
      final Instant updatedAt) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.platformAccountId =
        Objects.requireNonNull(platformAccountId, "platformAccountId must not be null");
    this.passwordHash = Objects.requireNonNull(passwordHash, "passwordHash must not be null");
    this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    if (passwordHash.isBlank()) {
      throw new IllegalArgumentException("passwordHash must not be blank");
    }
  }

  public static PlatformPasswordCredential issue(
      final PlatformAccountId platformAccountId, final String passwordHash) {
    return new PlatformPasswordCredential(
        UUID.randomUUID(), platformAccountId, passwordHash, Instant.now());
  }

  public static PlatformPasswordCredential reconstitute(
      final UUID id,
      final PlatformAccountId platformAccountId,
      final String passwordHash,
      final Instant updatedAt) {
    return new PlatformPasswordCredential(id, platformAccountId, passwordHash, updatedAt);
  }

  public UUID id() {
    return id;
  }

  public PlatformAccountId platformAccountId() {
    return platformAccountId;
  }

  public String passwordHash() {
    return passwordHash;
  }

  public Instant updatedAt() {
    return updatedAt;
  }
}
