package com.clavaris.identity.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link PendingSocialLink}'s platform-tier sibling — same ADR-0020 Decision 1/BR-ID-09 invariants,
 * scoped to a {@link PlatformAccount} instead of an {@link Account}. See {@link
 * PendingSocialLink}'s own Javadoc for the full linking-confirmation reasoning; only the owning-id
 * type differs here, same mirroring convention {@link PlatformVerificationToken} already
 * establishes for its own pair.
 *
 * <p>Same record-style-accessor PMD suppressions as every other value object in this codebase.
 * PMD.LongVariable: same {@code confirmationTokenHash} rationale {@link PendingSocialLink}'s own
 * identical suppression documents.
 */
@SuppressWarnings({
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.ShortVariable",
  "PMD.ShortMethodName",
  "PMD.LongVariable"
})
public final class PendingPlatformSocialLink {

  private final UUID id;
  private final PlatformAccountId platformAccountId;
  private final SocialProvider provider;
  private final String providerUserId;
  private final String confirmationTokenHash;
  private final Instant expiresAt;
  private Instant consumedAt;

  private PendingPlatformSocialLink(
      final UUID id,
      final PlatformAccountId platformAccountId,
      final SocialProvider provider,
      final String providerUserId,
      final String confirmationTokenHash,
      final Instant expiresAt,
      final Instant consumedAt) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.platformAccountId =
        Objects.requireNonNull(platformAccountId, "platformAccountId must not be null");
    this.provider = Objects.requireNonNull(provider, "provider must not be null");
    this.providerUserId = Objects.requireNonNull(providerUserId, "providerUserId must not be null");
    this.confirmationTokenHash =
        Objects.requireNonNull(confirmationTokenHash, "confirmationTokenHash must not be null");
    this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    this.consumedAt = consumedAt;
  }

  public static PendingPlatformSocialLink raise(
      final PlatformAccountId platformAccountId,
      final SocialProvider provider,
      final String providerUserId,
      final String confirmationTokenHash,
      final Instant expiresAt) {
    return new PendingPlatformSocialLink(
        UUID.randomUUID(),
        platformAccountId,
        provider,
        providerUserId,
        confirmationTokenHash,
        expiresAt,
        null);
  }

  public static PendingPlatformSocialLink reconstitute(
      final UUID id,
      final PlatformAccountId platformAccountId,
      final SocialProvider provider,
      final String providerUserId,
      final String confirmationTokenHash,
      final Instant expiresAt,
      final Instant consumedAt) {
    return new PendingPlatformSocialLink(
        id,
        platformAccountId,
        provider,
        providerUserId,
        confirmationTokenHash,
        expiresAt,
        consumedAt);
  }

  public void consume() {
    this.consumedAt = Instant.now();
  }

  public boolean isActive() {
    return consumedAt == null && expiresAt.isAfter(Instant.now());
  }

  public UUID id() {
    return id;
  }

  public PlatformAccountId platformAccountId() {
    return platformAccountId;
  }

  public SocialProvider provider() {
    return provider;
  }

  public String providerUserId() {
    return providerUserId;
  }

  public String confirmationTokenHash() {
    return confirmationTokenHash;
  }

  public Instant expiresAt() {
    return expiresAt;
  }

  public Optional<Instant> consumedAt() {
    return Optional.ofNullable(consumedAt);
  }
}
