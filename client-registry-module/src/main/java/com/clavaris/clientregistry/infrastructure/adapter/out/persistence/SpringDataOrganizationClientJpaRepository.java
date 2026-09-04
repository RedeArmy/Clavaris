package com.clavaris.clientregistry.infrastructure.adapter.out.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataOrganizationClientJpaRepository
    extends JpaRepository<OrganizationClientEntity, UUID> {

  Optional<OrganizationClientEntity> findByClientId(String clientId);

  List<OrganizationClientEntity> findAllByOrganizationId(UUID organizationId);

  void deleteAllByOrganizationId(UUID organizationId);
}
