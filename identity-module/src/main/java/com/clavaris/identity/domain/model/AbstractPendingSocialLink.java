package com.clavaris.identity.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Shared state and lifecycle for {@link PendingSocialLink}/{@link PendingPlatformSocialLink} —
 * SonarCloud-flagged duplication (TD-ARCH-009, widened 2026-08-30: 13.5%/14.5%, 18 lines each)
 * extracted here once it was clear {@link #consume()}/{@link #isActive()} and every field except
 * the owning id are provably, permanently identical between the two: BR-ID-09's "single-use,
 * time-limited confirmation" invariant is the same security property for both tiers, not a
 * coincidence either copy could plausibly diverge from later ({@code coding-standards.md} §5's own
 * "would only ever change in lockstep" test).
 *
 * <p>Deliberately different from {@code AuthenticateWithSocialProviderService}/{@code
 * AuthenticatePlatformAccountWithSocialProviderService} (TD-ARCH-009's own original scope, left as
 * a mirror, not shared): those orchestrate real, plausibly-diverging side effects per tier
 * (different repositories, a different mail sender, an outbox write only one tier has) — an
 * inheritance hierarchy across genuinely different aggregate types would be the wrong tool there.
 * This class has no side effects and no tier-specific behavior at all; {@code I} is the only thing
 * that differs, which generics express directly without pretending {@link Account} and {@link
 * PlatformAccount} share anything they don't.
 *
 * <p>Package-private: only this package's own two subclasses ever need to see it. Same
 * record-style-accessor PMD suppressions as every other value object in this codebase.
 * PMD.PublicMemberInNonPublicType: every public member here is inherited and re-exposed by this
 * package's own public subclasses ({@link PendingSocialLink}/{@link PendingPlatformSocialLink}) — a
 * caller outside this package reaches every one of them through that public type, PMD's static
 * check just can't see across the hierarchy. PMD.DataClass: PMD's own weight-of-class metric counts
 * seven accessors against two real behavior methods (consume()/isActive()) and calls that
 * data-class-shaped — a false positive here specifically because this class was extracted to carry
 * state two other aggregates share, not because it lacks behavior of its own.
 */
@SuppressWarnings({
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.ShortVariable",
  "PMD.ShortMethodName",
  "PMD.LongVariable",
  "PMD.AbstractClassWithoutAbstractMethod",
  "PMD.PublicMemberInNonPublicType",
  "PMD.DataClass"
})
abstract class AbstractPendingSocialLink<I> {

  private final UUID id;
  private final I owningId;
  private final SocialProvider provider;
  private final String providerUserId;
  private final String confirmationTokenHash;
  private final Instant expiresAt;
  private Instant consumedAt;

  protected AbstractPendingSocialLink(
      final UUID id,
      final I owningId,
      final SocialProvider provider,
      final String providerUserId,
      final String confirmationTokenHash,
      final Instant expiresAt,
      final Instant consumedAt) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.owningId = Objects.requireNonNull(owningId, "owningId must not be null");
    this.provider = Objects.requireNonNull(provider, "provider must not be null");
    this.providerUserId = Objects.requireNonNull(providerUserId, "providerUserId must not be null");
    this.confirmationTokenHash =
        Objects.requireNonNull(confirmationTokenHash, "confirmationTokenHash must not be null");
    this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    this.consumedAt = consumedAt;
  }

  /** BR-ID-09: single-use — a successful confirmation consumes the pending link. */
  public final void consume() {
    this.consumedAt = Instant.now();
  }

  /** Not consumed and not naturally expired — the only state a confirmation may succeed from. */
  public final boolean isActive() {
    return consumedAt == null && expiresAt.isAfter(Instant.now());
  }

  public final UUID id() {
    return id;
  }

  protected final I owningId() {
    return owningId;
  }

  public final SocialProvider provider() {
    return provider;
  }

  public final String providerUserId() {
    return providerUserId;
  }

  public final String confirmationTokenHash() {
    return confirmationTokenHash;
  }

  public final Instant expiresAt() {
    return expiresAt;
  }

  public final Optional<Instant> consumedAt() {
    return Optional.ofNullable(consumedAt);
  }
}
