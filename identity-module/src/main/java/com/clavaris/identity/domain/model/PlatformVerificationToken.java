package com.clavaris.identity.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * {@link VerificationToken}'s platform-tier sibling — same BR-ID-04/BR-ID-05 invariants
 * (single-use, time-limited, hash-only), scoped to a {@link PlatformAccount} instead of an {@link
 * Account}. Reuses {@link VerificationTokenType} as-is (the discriminator's two values, {@code
 * EMAIL_VERIFICATION}/{@code PASSWORD_RESET}, are identical in meaning at either tier) — only the
 * owning-id type differs, which is exactly the one thing a shared type couldn't express safely.
 *
 * <p>Shared state/lifecycle lives on {@link AbstractVerificationToken} — see its own Javadoc for
 * why this pair shares a base (TD-ARCH-009).
 *
 * <p>PMD.ShortVariable: {@code id} names exactly what it is — same convention {@link
 * AbstractVerificationToken}'s own identical suppression already documents for this same
 * constructor parameter.
 */
@SuppressWarnings("PMD.ShortVariable")
public final class PlatformVerificationToken extends AbstractVerificationToken<PlatformAccountId> {

  private PlatformVerificationToken(
      final UUID id,
      final PlatformAccountId platformAccountId,
      final VerificationTokenType type,
      final String tokenHash,
      final Instant expiresAt,
      final Instant consumedAt) {
    super(id, platformAccountId, type, tokenHash, expiresAt, consumedAt);
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

  public PlatformAccountId platformAccountId() {
    return owningId();
  }
}
