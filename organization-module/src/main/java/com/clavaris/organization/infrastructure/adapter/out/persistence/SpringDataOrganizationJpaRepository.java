package com.clavaris.organization.infrastructure.adapter.out.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

// existsById(UUID) is already declared by CrudRepository — nothing to add for it.
interface SpringDataOrganizationJpaRepository extends JpaRepository<OrganizationEntity, UUID> {

  List<OrganizationEntity> findAllByOwnerPlatformAccountId(
      @SuppressWarnings("PMD.LongVariable") UUID ownerPlatformAccountId);
}
