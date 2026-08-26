package com.clavaris.clientregistry.infrastructure.adapter.out.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataOAuthClientJpaRepository extends JpaRepository<OAuthClientEntity, UUID> {

  Optional<OAuthClientEntity> findByClientId(String clientId);

  // BR-DATA-02/03's own organization-level equivalent — every OAuthClient this Organization ever
  // registered.
  void deleteAllByOrganizationId(UUID organizationId);
}
