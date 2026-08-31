package com.clavaris.identity.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * BR-ID-04/BR-ID-05: single-use, time-limited, delivered only to the email address of record — the
 * raw value is never stored, only {@code tokenHash}, same hash-not-plaintext principle as {@link
 * PasswordCredential} and {@link RefreshToken}. One model serves both email verification and
 * password reset (domain-model.md §2) — {@code type} discriminates what consuming the token does;
 * the token's own lifecycle (issue once, consume once, expire) is identical either way.
 *
 * <p>Shared state/lifecycle (every field except {@link #accountId()}, plus {@code consume()}/
 * {@code isActive()}) lives on {@link AbstractVerificationToken} — see its own Javadoc for why this
 * pair shares a base (TD-ARCH-009).
 *
 * <p>PMD.ShortVariable: {@code id} names exactly what it is — same convention {@link
 * AbstractVerificationToken}'s own identical suppression already documents for this same
 * constructor parameter.
 */
@SuppressWarnings("PMD.ShortVariable")
public final class VerificationToken extends AbstractVerificationToken<AccountId> {

  private VerificationToken(
      final UUID id,
      final AccountId accountId,
      final VerificationTokenType type,
      final String tokenHash,
      final Instant expiresAt,
      final Instant consumedAt) {
    super(id, accountId, type, tokenHash, expiresAt, consumedAt);
  }

  /** A freshly-requested token — {@link #consumedAt()} is empty until {@link #consume()}. */
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

  public AccountId accountId() {
    return owningId();
  }
}
