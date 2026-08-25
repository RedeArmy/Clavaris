package com.clavaris.identity.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataEventOutboxJpaRepository extends JpaRepository<EventOutboxEntity, UUID> {

  // TD-TEST-002 (EventOutboxRetentionJob, infrastructure/config): counted separately from the
  // delete below purely so the sweep can WARN when it discards a row nothing has consumed yet —
  // see that class's own Javadoc for why this distinction matters.
  long countByOccurredAtBeforeAndPublishedAtIsNull(Instant cutoff);

  // Deliberately a bulk JPQL DELETE, not Spring Data's derived deleteBy...(...) convention: the
  // derived form loads every matching row into the persistence context one at a time before
  // removing each individually — fine for a handful of rows, wasteful (and needlessly memory-
  // heavy) for a retention sweep that's explicitly meant to handle unbounded accumulation. A bulk
  // statement deletes directly against the database in one round trip and needs no explicit flush
  // for a caller reading the result back through a different connection (e.g. a test's own
  // JdbcTemplate) to see it.
  @Modifying
  @Query("delete from EventOutboxEntity e where e.occurredAt < :cutoff")
  long deleteByOccurredAtBefore(@Param("cutoff") Instant cutoff);
}
