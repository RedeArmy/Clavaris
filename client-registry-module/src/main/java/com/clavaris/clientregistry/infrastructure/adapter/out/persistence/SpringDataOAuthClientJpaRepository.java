package com.clavaris.clientregistry.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataOAuthClientJpaRepository extends JpaRepository<OAuthClientEntity, UUID> {

  Optional<OAuthClientEntity> findByClientId(String clientId);

  List<OAuthClientEntity> findAllByOrganizationId(UUID organizationId);

  // BR-DATA-02/03's own organization-level equivalent — every OAuthClient this Organization ever
  // registered.
  void deleteAllByOrganizationId(UUID organizationId);

  // TD-PERF-020 (keyset revision, 2026-09-14): backs
  // OAuthClientRepository#findKeysetPageByOrganizationId — see
  // SpringDataOrganizationJpaRepository's own Javadoc (organization-module) for why three @Query
  // methods back this instead of one Pageable-driven derived method.
  @Query(
      """
      SELECT c FROM OAuthClientEntity c
      WHERE c.organizationId = :organizationId
      ORDER BY c.createdAt DESC, c.id DESC
      """)
  List<OAuthClientEntity> findFirstPageByOrganizationId(
      @Param("organizationId") UUID organizationId, Pageable pageable);

  @Query(
      """
      SELECT c FROM OAuthClientEntity c
      WHERE c.organizationId = :organizationId
        AND (c.createdAt < :cursorCreatedAt
             OR (c.createdAt = :cursorCreatedAt AND c.id < :cursorId))
      ORDER BY c.createdAt DESC, c.id DESC
      """)
  List<OAuthClientEntity> findPageByOrganizationIdAfter(
      @Param("organizationId") UUID organizationId,
      @Param("cursorCreatedAt") Instant cursorCreatedAt,
      @Param("cursorId") UUID cursorId,
      Pageable pageable);

  @Query(
      """
      SELECT c FROM OAuthClientEntity c
      WHERE c.organizationId = :organizationId
        AND (c.createdAt > :cursorCreatedAt
             OR (c.createdAt = :cursorCreatedAt AND c.id > :cursorId))
      ORDER BY c.createdAt ASC, c.id ASC
      """)
  List<OAuthClientEntity> findPageByOrganizationIdBefore(
      @Param("organizationId") UUID organizationId,
      @Param("cursorCreatedAt") Instant cursorCreatedAt,
      @Param("cursorId") UUID cursorId,
      Pageable pageable);
}
