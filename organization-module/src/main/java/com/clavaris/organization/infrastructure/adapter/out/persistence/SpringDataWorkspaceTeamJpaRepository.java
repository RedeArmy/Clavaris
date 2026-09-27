package com.clavaris.organization.infrastructure.adapter.out.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataWorkspaceTeamJpaRepository extends JpaRepository<WorkspaceTeamEntity, UUID> {

  List<WorkspaceTeamEntity> findAllByWorkspaceId(UUID workspaceId);
}
