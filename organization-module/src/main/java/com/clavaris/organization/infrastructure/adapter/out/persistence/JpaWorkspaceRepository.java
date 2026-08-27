package com.clavaris.organization.infrastructure.adapter.out.persistence;

import com.clavaris.organization.application.usecase.createworkspace.WorkspaceRepository;
import com.clavaris.organization.domain.model.Workspace;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Implements the outbound port; maps between {@code domain.model.Workspace} and {@link
 * WorkspaceEntity}.
 */
@Repository
class JpaWorkspaceRepository implements WorkspaceRepository {

  private final SpringDataWorkspaceJpaRepository workspaces;

  /* package */ JpaWorkspaceRepository(final SpringDataWorkspaceJpaRepository workspaces) {
    this.workspaces = workspaces;
  }

  @Override
  public void save(final Workspace workspace) {
    workspaces.save(
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

  private Workspace toDomain(final WorkspaceEntity entity) {
    return Workspace.reconstitute(
        entity.getId(), entity.getOrganizationId(), entity.getName(), entity.getCreatedAt());
  }
}
