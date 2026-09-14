package com.clavaris.organization.infrastructure.adapter.out.persistence;

import com.clavaris.common.domain.model.KeysetCursor;
import com.clavaris.common.domain.model.KeysetPage;
import com.clavaris.common.domain.model.KeysetPageRequest;
import com.clavaris.common.infrastructure.adapter.out.persistence.SpringDataKeysetPageMapper;
import com.clavaris.organization.application.usecase.createworkspace.WorkspaceRepository;
import com.clavaris.organization.domain.model.Workspace;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements the outbound port; maps between {@code domain.model.Workspace} and {@link
 * WorkspaceEntity}.
 *
 * <p>TD-PERF-019: {@code save} calls {@link EntityManager#persist} directly, not {@code
 * SpringDataWorkspaceJpaRepository#save} — {@code WorkspaceRepository}'s own interface has no
 * update-shaped method at all (a Workspace is never renamed or otherwise mutated after creation in
 * v1, confirmed by reading every caller of this port), so {@code save} is genuinely insert-only and
 * {@code merge()}'s pre-existence {@code SELECT} was pure waste here. {@code @Transactional} on
 * {@code save} itself, not just at the caller: unlike {@code SimpleJpaRepository#save} (which
 * carries its own class-level {@code @Transactional} and so always runs inside some transaction
 * regardless of the caller), a raw {@link EntityManager#persist} call requires one already open on
 * the current thread — confirmed live, not assumed, by a real {@code TransactionRequiredException}
 * from a test calling this method with no surrounding transaction — same "must not depend on the
 * caller already having one open" reasoning {@code JpaAccountRepository#save}'s own identical
 * annotation already documents.
 */
@Repository
class JpaWorkspaceRepository implements WorkspaceRepository {

  private final SpringDataWorkspaceJpaRepository workspaces;
  private final EntityManager entityManager;

  /* package */ JpaWorkspaceRepository(
      final SpringDataWorkspaceJpaRepository workspaces, final EntityManager entityManager) {
    this.workspaces = workspaces;
    this.entityManager = entityManager;
  }

  @Override
  @Transactional
  public void save(final Workspace workspace) {
    entityManager.persist(
        new WorkspaceEntity(
            workspace.id(), workspace.organizationId(), workspace.name(), workspace.createdAt()));
  }

  @Override
  public Optional<Workspace> findById(final UUID workspaceId) {
    return workspaces.findById(workspaceId).map(this::toDomain);
  }

  @Override
  public List<Workspace> findAllByOrganizationId(final UUID organizationId) {
    return workspaces.findAllByOrganizationId(organizationId).stream().map(this::toDomain).toList();
  }

  @Override
  public Optional<UUID> findOrganizationIdById(final UUID workspaceId) {
    return workspaces.findOrganizationIdById(workspaceId);
  }

  // TD-PERF-020 (keyset revision, 2026-09-14): newest-first, id as a tiebreaker — same reasoning
  // JpaOrganizationRepository's own identical findKeysetPageOwnedBy already documents.
  @Override
  @SuppressWarnings("PMD.OnlyOneReturn") // three real, distinct exits — first/after/before.
  public KeysetPage<Workspace> findKeysetPageByOrganizationId(
      final UUID organizationId, final KeysetPageRequest pageRequest) {
    final org.springframework.data.domain.PageRequest limit =
        org.springframework.data.domain.PageRequest.of(0, pageRequest.size() + 1);
    if (pageRequest.after() != null) {
      final KeysetCursor cursor = pageRequest.after();
      return SpringDataKeysetPageMapper.forward(
          workspaces.findPageByOrganizationIdAfter(
              organizationId, cursor.createdAt(), cursor.id(), limit),
          pageRequest.size(),
          true,
          this::toDomain,
          this::cursorOf);
    }
    if (pageRequest.before() != null) {
      final KeysetCursor cursor = pageRequest.before();
      return SpringDataKeysetPageMapper.backward(
          workspaces.findPageByOrganizationIdBefore(
              organizationId, cursor.createdAt(), cursor.id(), limit),
          pageRequest.size(),
          this::toDomain,
          this::cursorOf);
    }
    return SpringDataKeysetPageMapper.forward(
        workspaces.findFirstPageByOrganizationId(organizationId, limit),
        pageRequest.size(),
        false,
        this::toDomain,
        this::cursorOf);
  }

  private KeysetCursor cursorOf(final WorkspaceEntity entity) {
    return new KeysetCursor(entity.getCreatedAt(), entity.getId());
  }

  private Workspace toDomain(final WorkspaceEntity entity) {
    return Workspace.reconstitute(
        entity.getId(), entity.getOrganizationId(), entity.getName(), entity.getCreatedAt());
  }
}
