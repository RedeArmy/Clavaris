package com.clavaris.webhook.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataWebhookDeliveryJpaRepository
    extends JpaRepository<WebhookDeliveryEntity, UUID> {

  // TD-PERF-012: a Pageable-bearing derived query, not a plain findAllBy — Spring Data pushes
  // Pageable#getPageSize()/getOffset() into the generated SQL's own LIMIT/OFFSET, so Postgres
  // itself stops returning (and Hibernate stops hydrating, full payload column included) rows
  // past the requested page — the caller's own .stream().limit(...) this replaces only ever
  // truncated in Java, after every row this endpoint ever recorded had already made the trip.
  List<WebhookDeliveryEntity> findByEndpointIdOrderByCreatedAtDesc(
      UUID endpointId, Pageable pageable);

  // DeliverPendingWebhooksService's own claim step, part 1: lock and select the ids of every row
  // due right now — FOR UPDATE SKIP LOCKED, safe for more than one dispatcher instance polling
  // concurrently (ADR-0007 §1's own NFR concurrency note), same pattern the outbox tables'
  // dispatcher-facing read side already uses. JPQL has no FOR UPDATE SKIP LOCKED syntax, hence
  // native.
  @Query(
      value =
          "select id from webhook_deliveries where (status = 'PENDING' or (status = 'FAILED' and"
              + " next_attempt_at <= :now)) order by next_attempt_at asc limit :limit for update"
              + " skip locked",
      nativeQuery = true)
  List<UUID> selectDueIdsForUpdateSkipLocked(@Param("now") Instant now, @Param("limit") int limit);

  // Part 2: lease every claimed id — see WebhookDelivery.lease's own Javadoc for why this happens
  // inside the same short transaction as the select above, before any network I/O.
  @Modifying
  @Query("update WebhookDeliveryEntity d set d.nextAttemptAt = :leaseUntil where d.id in :ids")
  void leaseByIds(@Param("ids") List<UUID> ids, @Param("leaseUntil") Instant leaseUntil);

  List<WebhookDeliveryEntity> findByIdIn(List<UUID> ids);

  // WebhookDeliveryRetentionJob's own sweep — terminal rows only (SUCCEEDED/EXHAUSTED); PENDING/
  // FAILED rows are never swept regardless of age, since they may still be legitimately due for a
  // future retry.
  long deleteByCreatedAtBeforeAndStatusIn(Instant cutoff, List<String> statuses);
}
