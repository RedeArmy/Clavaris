package com.clavaris.organization.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataWorkspaceJpaRepository extends JpaRepository<WorkspaceEntity, UUID> {

  List<WorkspaceEntity> findAllByOrganizationId(UUID organizationId);

  // Same "scalar projection over full-entity lookup" precedent as identity-module's own
  // SpringDataAccountJpaRepository#findOrganizationIdById.
  @Query("select w.organizationId from WorkspaceEntity w where w.id = :workspaceId")
  Optional<UUID> findOrganizationIdById(@Param("workspaceId") UUID workspaceId);

  // TD-PERF-020 (keyset revision, 2026-09-14): backs
  // WorkspaceRepository#findKeysetPageByOrganizationId
  // — see SpringDataOrganizationJpaRepository's own Javadoc for why three @Query methods back this
  // instead of one Pageable-driven derived method.
  @Query(
      """
      SELECT w FROM WorkspaceEntity w
      WHERE w.organizationId = :organizationId
      ORDER BY w.createdAt DESC, w.id DESC
      """)
  List<WorkspaceEntity> findFirstPageByOrganizationId(
      @Param("organizationId") UUID organizationId, Pageable pageable);

  @Query(
      """
      SELECT w FROM WorkspaceEntity w
      WHERE w.organizationId = :organizationId
        AND (w.createdAt < :cursorCreatedAt
             OR (w.createdAt = :cursorCreatedAt AND w.id < :cursorId))
      ORDER BY w.createdAt DESC, w.id DESC
      """)
  List<WorkspaceEntity> findPageByOrganizationIdAfter(
      @Param("organizationId") UUID organizationId,
      @Param("cursorCreatedAt") Instant cursorCreatedAt,
      @Param("cursorId") UUID cursorId,
      Pageable pageable);

  @Query(
      """
      SELECT w FROM WorkspaceEntity w
      WHERE w.organizationId = :organizationId
        AND (w.createdAt > :cursorCreatedAt
             OR (w.createdAt = :cursorCreatedAt AND w.id > :cursorId))
      ORDER BY w.createdAt ASC, w.id ASC
      """)
  List<WorkspaceEntity> findPageByOrganizationIdBefore(
      @Param("organizationId") UUID organizationId,
      @Param("cursorCreatedAt") Instant cursorCreatedAt,
      @Param("cursorId") UUID cursorId,
      Pageable pageable);
}
