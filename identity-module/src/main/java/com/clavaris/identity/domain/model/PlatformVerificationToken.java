package com.clavaris.identity.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link VerificationToken}'s platform-tier sibling — same BR-ID-04/BR-ID-05 invariants
 * (single-use, time-limited, hash-only), scoped to a {@link PlatformAccount} instead of an {@link
 * Account}. Reuses {@link VerificationTokenType} as-is (the discriminator's two values, {@code
 * EMAIL_VERIFICATION}/{@code PASSWORD_RESET}, are identical in meaning at either tier) — only the
 * owning-id type differs, which is exactly the one thing a shared type couldn't express safely.
 *
 * <p>Same record-style-accessor PMD suppressions as {@link VerificationToken}, same rationale.
 */
@SuppressWarnings({
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.ShortVariable",
  "PMD.ShortMethodName"
})
public final class PlatformVerificationToken {

  private final UUID id;
  private final PlatformAccountId platformAccountId;
  private final VerificationTokenType type;
  private final String tokenHash;
  private final Instant expiresAt;
  private Instant consumedAt;

  private PlatformVerificationToken(
      final UUID id,
      final PlatformAccountId platformAccountId,
      final VerificationTokenType type,
      final String tokenHash,
      final Instant expiresAt,
      final Instant consumedAt) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.platformAccountId =
        Objects.requireNonNull(platformAccountId, "platformAccountId must not be null");
    this.type = Objects.requireNonNull(type, "type must not be null");
    this.tokenHash = Objects.requireNonNull(tokenHash, "tokenHash must not be null");
    this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    this.consumedAt = consumedAt;
  }

  public static PlatformVerificationToken issue(
      final PlatformAccountId platformAccountId,
      final VerificationTokenType type,
      final String tokenHash,
      final Instant expiresAt) {
    return new PlatformVerificationToken(
        UUID.randomUUID(), platformAccountId, type, tokenHash, expiresAt, null);
  }

  public static PlatformVerificationToken reconstitute(
      final UUID id,
      final PlatformAccountId platformAccountId,
      final VerificationTokenType type,
      final String tokenHash,
      final Instant expiresAt,
      final Instant consumedAt) {
    return new PlatformVerificationToken(
        id, platformAccountId, type, tokenHash, expiresAt, consumedAt);
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

  public VerificationTokenType type() {
    return type;
  }

  public String tokenHash() {
    return tokenHash;
  }

  public Instant expiresAt() {
    return expiresAt;
  }

  public Optional<Instant> consumedAt() {
    return Optional.ofNullable(consumedAt);
  }
}
