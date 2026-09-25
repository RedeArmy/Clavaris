package com.clavaris.webhook.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/*
 * Live UX request, 2026-09-25 (Clerk-parity org-wide Logs/Activity): the four keyset methods below
 * back WebhookDeliveryRepository#findKeysetPageByOrganizationId — copied from
 * SpringDataWebhookEndpointJpaRepository's own identical three-@Query shape (see that interface's
 * own Javadoc for why three @Query methods back one keyset page instead of a single
 * Pageable-driven derived method). findAllByOrganizationIdAndLastAttemptAtGreaterThanEqual backs
 * the Activity page's own hourly-bucket aggregation, computed in Java (not SQL GROUP BY) — same
 * "correct and simple first" posture WebhookEndpointRepository#findActiveByOrganizationIdAndEventType's
 * own in-memory filter already documents, bounded to a short recent window (a handful of hours),
 * never the whole table.
 */

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
  //
  // Correctness bug fixed (SDE-III review, 2026-09-14): the PENDING branch used to have no
  // next_attempt_at filter at all, so WebhookDelivery.lease() — which only ever pushes
  // next_attempt_at into the future and never changes status — was silently not honored for
  // PENDING rows. A row claimed by one dispatcher tick (still PENDING, HTTP attempt in flight or
  // stuck on an uncaught exception) was immediately re-claimable by the very next tick, causing
  // real duplicate delivery under horizontal scaling and a tight, backoff-free retry loop instead
  // of respecting delivery-claim-lease. Both branches now share the identical next_attempt_at <=
  // :now gate — a freshly scheduled or replayed row is unaffected (schedule()/resetForReplay()
  // both stamp next_attempt_at = now, already <= any later poll), but a just-leased row correctly
  // stays invisible to this query until its lease actually expires.
  @Query(
      value =
          "select id from webhook_deliveries where status in ('PENDING', 'FAILED') and"
              + " next_attempt_at <= :now order by next_attempt_at asc limit :limit for update"
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

  void deleteAllByOrganizationId(UUID organizationId);

  List<WebhookDeliveryEntity> findAllByOrganizationIdAndLastAttemptAtGreaterThanEqual(
      UUID organizationId, Instant since);

  @Query(
      """
      SELECT d FROM WebhookDeliveryEntity d
      WHERE d.organizationId = :organizationId
      ORDER BY d.createdAt DESC, d.id DESC
      """)
  List<WebhookDeliveryEntity> findFirstPageByOrganizationId(
      @Param("organizationId") UUID organizationId, Pageable pageable);

  @Query(
      """
      SELECT d FROM WebhookDeliveryEntity d
      WHERE d.organizationId = :organizationId
        AND (d.createdAt < :cursorCreatedAt
             OR (d.createdAt = :cursorCreatedAt AND d.id < :cursorId))
      ORDER BY d.createdAt DESC, d.id DESC
      """)
  List<WebhookDeliveryEntity> findPageByOrganizationIdAfter(
      @Param("organizationId") UUID organizationId,
      @Param("cursorCreatedAt") Instant cursorCreatedAt,
      @Param("cursorId") UUID cursorId,
      Pageable pageable);

  @Query(
      """
      SELECT d FROM WebhookDeliveryEntity d
      WHERE d.organizationId = :organizationId
        AND (d.createdAt > :cursorCreatedAt
             OR (d.createdAt = :cursorCreatedAt AND d.id > :cursorId))
      ORDER BY d.createdAt ASC, d.id ASC
      """)
  List<WebhookDeliveryEntity> findPageByOrganizationIdBefore(
      @Param("organizationId") UUID organizationId,
      @Param("cursorCreatedAt") Instant cursorCreatedAt,
      @Param("cursorId") UUID cursorId,
      Pageable pageable);
}
