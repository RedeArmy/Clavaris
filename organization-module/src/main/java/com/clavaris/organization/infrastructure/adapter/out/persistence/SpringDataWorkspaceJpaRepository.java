package com.clavaris.organization.infrastructure.adapter.out.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataWorkspaceJpaRepository extends JpaRepository<WorkspaceEntity, UUID> {

  List<WorkspaceEntity> findAllByOrganizationId(UUID organizationId);

  // Same "scalar projection over full-entity lookup" precedent as identity-module's own
  // SpringDataAccountJpaRepository#findOrganizationIdById.
  @Query("select w.organizationId from WorkspaceEntity w where w.id = :workspaceId")
  Optional<UUID> findOrganizationIdById(@Param("workspaceId") UUID workspaceId);
}
