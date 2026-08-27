package com.clavaris.organization.infrastructure.adapter.out.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataWorkspaceJpaRepository extends JpaRepository<WorkspaceEntity, UUID> {

  List<WorkspaceEntity> findAllByOrganizationId(UUID organizationId);
}
