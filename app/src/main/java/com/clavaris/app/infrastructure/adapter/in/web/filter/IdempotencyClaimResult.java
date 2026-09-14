package com.clavaris.app.infrastructure.adapter.in.web.filter;

/**
 * @param outcome what {@link IdempotencyKeyStore#claim} decided
 * @param cachedResponse populated only when {@code outcome} is {@link IdempotencyOutcome#REPLAY} —
 *     {@code null} for every other outcome, since there is nothing to replay yet (or ever, for
 *     {@link IdempotencyOutcome#CONFLICT}).
 */
public record IdempotencyClaimResult(
    IdempotencyOutcome outcome, IdempotentResponse cachedResponse) {

  public static IdempotencyClaimResult claimed() {
    return new IdempotencyClaimResult(IdempotencyOutcome.CLAIMED, null);
  }

  public static IdempotencyClaimResult inProgress() {
    return new IdempotencyClaimResult(IdempotencyOutcome.IN_PROGRESS, null);
  }

  public static IdempotencyClaimResult conflict() {
    return new IdempotencyClaimResult(IdempotencyOutcome.CONFLICT, null);
  }

  public static IdempotencyClaimResult replay(final IdempotentResponse cachedResponse) {
    return new IdempotencyClaimResult(IdempotencyOutcome.REPLAY, cachedResponse);
  }
}
