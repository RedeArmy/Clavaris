package com.clavaris.identity.infrastructure.adapter.out.persistence;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataWorkspaceMembershipReadJpaRepository
    extends JpaRepository<WorkspaceMembershipReadEntity, UUID> {

  List<WorkspaceMembershipReadEntity> findAllByAccountIdIn(Collection<UUID> accountIds);
}
