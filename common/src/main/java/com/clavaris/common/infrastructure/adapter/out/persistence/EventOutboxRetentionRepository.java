package com.clavaris.common.infrastructure.adapter.out.persistence;

import java.time.Instant;

/**
 * The two queries {@link EventOutboxRetentionSweeper} needs against any module's own event-outbox
 * table — each module's own Spring Data repository interface (e.g. identity-module's {@code
 * SpringDataEventOutboxJpaRepository}) additionally extends this, alongside its own {@code
 * JpaRepository<...>}, so Spring Data derives/binds these two methods exactly as it already did
 * before this extraction — this interface only names the shared shape, it changes no query.
 */
public interface EventOutboxRetentionRepository {

  /**
   * SDE-III review, 2026-09-15 — closes a real data-loss gap: the sweep used to only count
   * still-unpublished rows about to be purged (a WARN-level number), then delete them right along
   * with everything else — no way to ever recover or even inspect which specific events were lost.
   * This copies every row older than {@code cutoff} that was never published into this module's own
   * {@code *_dead_letters} table (one native {@code INSERT ... SELECT}, not a row-by-row Java-side
   * copy) before {@link #deleteByOccurredAtBefore} removes it from the live table, and returns how
   * many rows it archived — the sweeper still WARNs using that same count, but now the events
   * themselves survive the sweep for manual investigation or replay instead of being destroyed.
   */
  long archiveUnpublishedBefore(Instant cutoff);

  long deleteByOccurredAtBefore(Instant cutoff);
}
