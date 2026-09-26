package com.clavaris.organization.infrastructure.adapter.out.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataWorkspaceRoleJpaRepository extends JpaRepository<WorkspaceRoleEntity, UUID> {

  List<WorkspaceRoleEntity> findAllByOrganizationId(UUID organizationId);
}
