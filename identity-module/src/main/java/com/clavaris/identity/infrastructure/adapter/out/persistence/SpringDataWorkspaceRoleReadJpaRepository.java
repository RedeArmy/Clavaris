package com.clavaris.identity.infrastructure.adapter.out.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

// findAllById(Iterable<UUID>) — inherited from JpaRepository, no derived method of its own needed.
interface SpringDataWorkspaceRoleReadJpaRepository
    extends JpaRepository<WorkspaceRoleReadEntity, UUID> {}
