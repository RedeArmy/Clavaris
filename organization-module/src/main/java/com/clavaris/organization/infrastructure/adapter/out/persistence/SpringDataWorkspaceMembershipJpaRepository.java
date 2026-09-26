package com.clavaris.organization.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataWorkspaceMembershipJpaRepository
    extends JpaRepository<WorkspaceMembershipEntity, UUID> {

  Optional<WorkspaceMembershipEntity> findByWorkspaceIdAndAccountId(
      UUID workspaceId, UUID accountId);

  List<WorkspaceMembershipEntity> findAllByWorkspaceId(UUID workspaceId);

  // TD-PERF-021: backs WorkspaceMembershipRepository#findAllByWorkspaceIds — see that method's own
  // Javadoc.
  List<WorkspaceMembershipEntity> findAllByWorkspaceIdIn(Collection<UUID> workspaceIds);

  List<WorkspaceMembershipEntity> findAllByAccountId(UUID accountId);

  void deleteAllByAccountId(UUID accountId);

  // TD-PERF-020 (keyset revision, 2026-09-14): backs
  // WorkspaceMembershipRepository#findKeysetPageByWorkspaceId — see
  // SpringDataOrganizationJpaRepository's own Javadoc for why three @Query methods back this
  // instead of one Pageable-driven derived method.
  @Query(
      """
      SELECT m FROM WorkspaceMembershipEntity m
      WHERE m.workspaceId = :workspaceId
      ORDER BY m.createdAt DESC, m.id DESC
      """)
  List<WorkspaceMembershipEntity> findFirstPageByWorkspaceId(
      @Param("workspaceId") UUID workspaceId, Pageable pageable);

  @Query(
      """
      SELECT m FROM WorkspaceMembershipEntity m
      WHERE m.workspaceId = :workspaceId
        AND (m.createdAt < :cursorCreatedAt
             OR (m.createdAt = :cursorCreatedAt AND m.id < :cursorId))
      ORDER BY m.createdAt DESC, m.id DESC
      """)
  List<WorkspaceMembershipEntity> findPageByWorkspaceIdAfter(
      @Param("workspaceId") UUID workspaceId,
      @Param("cursorCreatedAt") Instant cursorCreatedAt,
      @Param("cursorId") UUID cursorId,
      Pageable pageable);

  @Query(
      """
      SELECT m FROM WorkspaceMembershipEntity m
      WHERE m.workspaceId = :workspaceId
        AND (m.createdAt > :cursorCreatedAt
             OR (m.createdAt = :cursorCreatedAt AND m.id > :cursorId))
      ORDER BY m.createdAt ASC, m.id ASC
      """)
  List<WorkspaceMembershipEntity> findPageByWorkspaceIdBefore(
      @Param("workspaceId") UUID workspaceId,
      @Param("cursorCreatedAt") Instant cursorCreatedAt,
      @Param("cursorId") UUID cursorId,
      Pageable pageable);
}
