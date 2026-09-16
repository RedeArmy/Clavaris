package com.clavaris.webhook.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataWebhookEndpointJpaRepository
    extends JpaRepository<WebhookEndpointEntity, UUID> {

  List<WebhookEndpointEntity> findAllByOrganizationId(UUID organizationId);

  List<WebhookEndpointEntity> findAllByOrganizationIdAndActiveTrue(UUID organizationId);

  // WebhookEndpointRepository#countByOrganizationId's own Javadoc — a derived COUNT query, not
  // findAllByOrganizationId(...).size(): counting must never pull every row's full column set just
  // to measure how many there are.
  long countByOrganizationId(UUID organizationId);

  void deleteAllByOrganizationId(UUID organizationId);

  // TD-PERF-020 (keyset revision, 2026-09-14): backs
  // WebhookEndpointRepository#findKeysetPageByOrganizationId — see
  // organization-module's SpringDataOrganizationJpaRepository's own Javadoc for why three @Query
  // methods back this instead of one Pageable-driven derived method.
  @Query(
      """
      SELECT e FROM WebhookEndpointEntity e
      WHERE e.organizationId = :organizationId
      ORDER BY e.createdAt DESC, e.id DESC
      """)
  List<WebhookEndpointEntity> findFirstPageByOrganizationId(
      @Param("organizationId") UUID organizationId, Pageable pageable);

  @Query(
      """
      SELECT e FROM WebhookEndpointEntity e
      WHERE e.organizationId = :organizationId
        AND (e.createdAt < :cursorCreatedAt
             OR (e.createdAt = :cursorCreatedAt AND e.id < :cursorId))
      ORDER BY e.createdAt DESC, e.id DESC
      """)
  List<WebhookEndpointEntity> findPageByOrganizationIdAfter(
      @Param("organizationId") UUID organizationId,
      @Param("cursorCreatedAt") Instant cursorCreatedAt,
      @Param("cursorId") UUID cursorId,
      Pageable pageable);

  @Query(
      """
      SELECT e FROM WebhookEndpointEntity e
      WHERE e.organizationId = :organizationId
        AND (e.createdAt > :cursorCreatedAt
             OR (e.createdAt = :cursorCreatedAt AND e.id > :cursorId))
      ORDER BY e.createdAt ASC, e.id ASC
      """)
  List<WebhookEndpointEntity> findPageByOrganizationIdBefore(
      @Param("organizationId") UUID organizationId,
      @Param("cursorCreatedAt") Instant cursorCreatedAt,
      @Param("cursorId") UUID cursorId,
      Pageable pageable);
}
