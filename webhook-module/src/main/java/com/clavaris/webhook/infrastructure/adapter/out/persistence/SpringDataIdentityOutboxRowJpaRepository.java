package com.clavaris.webhook.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataIdentityOutboxRowJpaRepository
    extends JpaRepository<IdentityOutboxRowEntity, UUID> {

  // ADR-0007 §1's own NFR concurrency note — FOR UPDATE SKIP LOCKED, safe for more than one
  // dispatcher instance polling concurrently.
  @Query(
      value =
          "select * from event_outbox where published_at is null order by occurred_at asc limit"
              + " :limit for update skip locked",
      nativeQuery = true)
  List<IdentityOutboxRowEntity> claimUnpublished(@Param("limit") int limit);

  // TD-PERF-013: one round trip for the whole batch, not one UPDATE per claimed row — same
  // WHERE id IN (...) bulk-update shape SpringDataWebhookDeliveryJpaRepository.leaseByIds already
  // established for this same "batch the write, not just the read" gap.
  @Modifying
  @Query("update IdentityOutboxRowEntity e set e.publishedAt = :publishedAt where e.id in :ids")
  void markPublishedBatch(@Param("ids") List<UUID> ids, @Param("publishedAt") Instant publishedAt);
}
