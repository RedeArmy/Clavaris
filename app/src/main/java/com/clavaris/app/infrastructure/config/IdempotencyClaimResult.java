package com.clavaris.app.infrastructure.config;

/**
 * @param outcome what {@link IdempotencyKeyStore#claim} decided
 * @param cachedResponse populated only when {@code outcome} is {@link IdempotencyOutcome#REPLAY} —
 *     {@code null} for every other outcome, since there is nothing to replay yet (or ever, for
 *     {@link IdempotencyOutcome#CONFLICT}).
 */
record IdempotencyClaimResult(IdempotencyOutcome outcome, IdempotentResponse cachedResponse) {

  /* package */ static IdempotencyClaimResult claimed() {
    return new IdempotencyClaimResult(IdempotencyOutcome.CLAIMED, null);
  }

  /* package */ static IdempotencyClaimResult inProgress() {
    return new IdempotencyClaimResult(IdempotencyOutcome.IN_PROGRESS, null);
  }

  /* package */ static IdempotencyClaimResult conflict() {
    return new IdempotencyClaimResult(IdempotencyOutcome.CONFLICT, null);
  }

  /* package */ static IdempotencyClaimResult replay(final IdempotentResponse cachedResponse) {
    return new IdempotencyClaimResult(IdempotencyOutcome.REPLAY, cachedResponse);
  }
}
