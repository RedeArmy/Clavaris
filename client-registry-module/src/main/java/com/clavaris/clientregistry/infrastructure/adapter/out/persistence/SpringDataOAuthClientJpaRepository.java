package com.clavaris.clientregistry.infrastructure.adapter.out.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataOAuthClientJpaRepository extends JpaRepository<OAuthClientEntity, UUID> {

  Optional<OAuthClientEntity> findByClientId(String clientId);

  List<OAuthClientEntity> findAllByOrganizationId(UUID organizationId);

  // BR-DATA-02/03's own organization-level equivalent — every OAuthClient this Organization ever
  // registered.
  void deleteAllByOrganizationId(UUID organizationId);
}
