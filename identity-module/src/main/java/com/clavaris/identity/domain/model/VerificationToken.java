package com.clavaris.identity.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * BR-ID-04/BR-ID-05: single-use, time-limited, delivered only to the email address of record — the
 * raw value is never stored, only {@link #tokenHash}, same hash-not-plaintext principle as {@link
 * PasswordCredential} and {@link RefreshToken}. One model serves both email verification and
 * password reset (domain-model.md §2) — {@link #type} discriminates what consuming the token does;
 * the token's own lifecycle (issue once, consume once, expire) is identical either way.
 *
 * <p>PMD's AvoidFieldNameMatchingMethodName/ShortVariable/ShortMethodName rules are suppressed for
 * the same reason as every other value object in this codebase (see {@code RefreshToken}'s own
 * Javadoc) — the deliberate record-style accessor convention used throughout.
 */
@SuppressWarnings({
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.ShortVariable",
  "PMD.ShortMethodName"
})
public final class VerificationToken {

  private final UUID id;
  private final AccountId accountId;
  private final VerificationTokenType type;
  private final String tokenHash;
  private final Instant expiresAt;
  private Instant consumedAt;

  private VerificationToken(
      final UUID id,
      final AccountId accountId,
      final VerificationTokenType type,
      final String tokenHash,
      final Instant expiresAt,
      final Instant consumedAt) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.accountId = Objects.requireNonNull(accountId, "accountId must not be null");
    this.type = Objects.requireNonNull(type, "type must not be null");
    this.tokenHash = Objects.requireNonNull(tokenHash, "tokenHash must not be null");
    this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    this.consumedAt = consumedAt;
  }

  /** A freshly-requested token — {@link #consumedAt} is empty until {@link #consume()}. */
  public static VerificationToken issue(
      final AccountId accountId,
      final VerificationTokenType type,
      final String tokenHash,
      final Instant expiresAt) {
    return new VerificationToken(UUID.randomUUID(), accountId, type, tokenHash, expiresAt, null);
  }

  public static VerificationToken reconstitute(
      final UUID id,
      final AccountId accountId,
      final VerificationTokenType type,
      final String tokenHash,
      final Instant expiresAt,
      final Instant consumedAt) {
    return new VerificationToken(id, accountId, type, tokenHash, expiresAt, consumedAt);
  }

  /** BR-ID-04/BR-ID-05: single-use — a successful verification/reset consumes the token. */
  public void consume() {
    this.consumedAt = Instant.now();
  }

  /** Not consumed and not naturally expired — the only state a confirm request may succeed from. */
  public boolean isActive() {
    return consumedAt == null && expiresAt.isAfter(Instant.now());
  }

  public UUID id() {
    return id;
  }

  public AccountId accountId() {
    return accountId;
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
