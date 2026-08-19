package com.clavaris.clientregistry.infrastructure.adapter.out.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataOAuthClientJpaRepository extends JpaRepository<OAuthClientEntity, UUID> {

  Optional<OAuthClientEntity> findByClientId(String clientId);
}
