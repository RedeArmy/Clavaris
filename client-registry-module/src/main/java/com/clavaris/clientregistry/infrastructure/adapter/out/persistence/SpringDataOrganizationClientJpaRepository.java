package com.clavaris.clientregistry.infrastructure.adapter.out.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataOrganizationClientJpaRepository
    extends JpaRepository<OrganizationClientEntity, UUID> {

  Optional<OrganizationClientEntity> findByClientId(String clientId);

  List<OrganizationClientEntity> findAllByOrganizationId(UUID organizationId);

  // TD-PERF-020: backs OrganizationClientRepository#findPageByOrganizationId.
  Page<OrganizationClientEntity> findAllByOrganizationId(UUID organizationId, Pageable pageable);

  void deleteAllByOrganizationId(UUID organizationId);
}
