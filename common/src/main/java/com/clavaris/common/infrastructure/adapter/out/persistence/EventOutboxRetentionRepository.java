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

  long countByOccurredAtBeforeAndPublishedAtIsNull(Instant cutoff);

  long deleteByOccurredAtBefore(Instant cutoff);
}
