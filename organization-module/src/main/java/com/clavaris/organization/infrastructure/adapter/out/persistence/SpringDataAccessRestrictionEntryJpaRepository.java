package com.clavaris.organization.infrastructure.adapter.out.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataAccessRestrictionEntryJpaRepository
    extends JpaRepository<AccessRestrictionEntryEntity, UUID> {

  boolean existsByOrganizationIdAndIdentifier(UUID organizationId, String identifier);

  List<AccessRestrictionEntryEntity> findAllByOrganizationId(UUID organizationId);
}
