package com.clavaris.identity.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Shared state and lifecycle for {@link VerificationToken}/{@link PlatformVerificationToken} —
 * TD-ARCH-009's own remaining follow-up (named 2026-08-30 when {@link AbstractPendingSocialLink}
 * was extracted for the structurally identical {@code PendingSocialLink}/{@code
 * PendingPlatformSocialLink} pair): {@link #consume()}/{@link #isActive()} and every field except
 * the owning id are provably, permanently identical between the two — BR-ID-04/BR-ID-05's
 * "single-use, time-limited, hash-only" invariant is the same security property at either tier, not
 * a coincidence either copy could plausibly diverge from later.
 *
 * <p>Same "generic, not a mirror" reasoning as {@link AbstractPendingSocialLink}'s own Javadoc —
 * this class has no side effects and no tier-specific behavior at all; {@code I} is the only thing
 * that differs.
 *
 * <p>Package-private: only this package's own two subclasses ever need to see it. Same
 * record-style-accessor and structural-metric PMD suppressions as {@link
 * AbstractPendingSocialLink}, same rationale.
 */
@SuppressWarnings({
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.ShortVariable",
  "PMD.ShortMethodName",
  "PMD.AbstractClassWithoutAbstractMethod",
  "PMD.PublicMemberInNonPublicType",
  "PMD.DataClass"
})
abstract class AbstractVerificationToken<I> {

  private final UUID id;
  private final I owningId;
  private final VerificationTokenType type;
  private final String tokenHash;
  private final Instant expiresAt;
  private Instant consumedAt;

  protected AbstractVerificationToken(
      final UUID id,
      final I owningId,
      final VerificationTokenType type,
      final String tokenHash,
      final Instant expiresAt,
      final Instant consumedAt) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.owningId = Objects.requireNonNull(owningId, "owningId must not be null");
    this.type = Objects.requireNonNull(type, "type must not be null");
    this.tokenHash = Objects.requireNonNull(tokenHash, "tokenHash must not be null");
    this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    this.consumedAt = consumedAt;
  }

  /** BR-ID-04/BR-ID-05: single-use — a successful verification/reset consumes the token. */
  public final void consume() {
    this.consumedAt = Instant.now();
  }

  /** Not consumed and not naturally expired — the only state a confirm request may succeed from. */
  public final boolean isActive() {
    return consumedAt == null && expiresAt.isAfter(Instant.now());
  }

  public final UUID id() {
    return id;
  }

  protected final I owningId() {
    return owningId;
  }

  public final VerificationTokenType type() {
    return type;
  }

  public final String tokenHash() {
    return tokenHash;
  }

  public final Instant expiresAt() {
    return expiresAt;
  }

  public final Optional<Instant> consumedAt() {
    return Optional.ofNullable(consumedAt);
  }
}
