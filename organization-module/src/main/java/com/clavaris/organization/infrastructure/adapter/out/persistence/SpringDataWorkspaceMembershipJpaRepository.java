package com.clavaris.organization.infrastructure.adapter.out.persistence;

import com.clavaris.organization.domain.model.WorkspaceRole;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataWorkspaceMembershipJpaRepository
    extends JpaRepository<WorkspaceMembershipEntity, UUID> {

  Optional<WorkspaceMembershipEntity> findByWorkspaceIdAndAccountId(
      UUID workspaceId, UUID accountId);

  List<WorkspaceMembershipEntity> findAllByWorkspaceId(UUID workspaceId);

  // TD-PERF-020: backs WorkspaceMembershipRepository#findPageByWorkspaceId.
  Page<WorkspaceMembershipEntity> findAllByWorkspaceId(UUID workspaceId, Pageable pageable);

  // TD-PERF-021: backs WorkspaceMembershipRepository#findAllByWorkspaceIds — see that method's own
  // Javadoc.
  List<WorkspaceMembershipEntity> findAllByWorkspaceIdIn(Collection<UUID> workspaceIds);

  List<WorkspaceMembershipEntity> findAllByAccountId(UUID accountId);

  long countByWorkspaceIdAndRole(UUID workspaceId, WorkspaceRole role);

  void deleteAllByAccountId(UUID accountId);
}
