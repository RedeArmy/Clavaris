package com.clavaris.clientregistry.infrastructure.adapter.out.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataClientBrandingJpaRepository extends JpaRepository<ClientBrandingEntity, UUID> {

  Optional<ClientBrandingEntity> findByOauthClientId(UUID oauthClientId);
}
