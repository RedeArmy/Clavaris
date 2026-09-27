package com.clavaris.organization.infrastructure.adapter.out.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataWorkspaceTeamRoleJpaRepository
    extends JpaRepository<WorkspaceTeamRoleEntity, WorkspaceTeamRoleId> {

  List<WorkspaceTeamRoleEntity> findAllByWorkspaceTeamId(UUID workspaceTeamId);

  List<WorkspaceTeamRoleEntity> findAllByWorkspaceTeamIdIn(List<UUID> workspaceTeamIds);

  List<WorkspaceTeamRoleEntity> findAllByWorkspaceRoleIdAndWorkspaceTeamIdIn(
      UUID workspaceRoleId, List<UUID> workspaceTeamIds);
}
