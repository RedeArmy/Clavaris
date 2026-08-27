package com.clavaris.organization.infrastructure.adapter.out.persistence;

import com.clavaris.organization.domain.model.WorkspaceRole;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataWorkspaceMembershipJpaRepository
    extends JpaRepository<WorkspaceMembershipEntity, UUID> {

  Optional<WorkspaceMembershipEntity> findByWorkspaceIdAndAccountId(
      UUID workspaceId, UUID accountId);

  List<WorkspaceMembershipEntity> findAllByWorkspaceId(UUID workspaceId);

  List<WorkspaceMembershipEntity> findAllByAccountId(UUID accountId);

  long countByWorkspaceIdAndRole(UUID workspaceId, WorkspaceRole role);

  void deleteAllByAccountId(UUID accountId);
}
