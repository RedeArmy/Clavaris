package com.clavaris.organization.infrastructure.adapter.out.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

// existsById(UUID) is already declared by CrudRepository — nothing to add for it.
interface SpringDataOrganizationJpaRepository extends JpaRepository<OrganizationEntity, UUID> {

  List<OrganizationEntity> findAllByOwnerPlatformAccountId(
      @SuppressWarnings("PMD.LongVariable") UUID ownerPlatformAccountId);

  // TD-PERF-020: backs OrganizationRepository#findPageOwnedBy — Spring Data auto-generates both
  // the page-of-rows query and its own separate COUNT query from this single derived method
  // (the Pageable parameter is what selects the Page-returning overload of the same base name).
  Page<OrganizationEntity> findAllByOwnerPlatformAccountId(
      @SuppressWarnings("PMD.LongVariable") UUID ownerPlatformAccountId, Pageable pageable);
}
