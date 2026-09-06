package com.clavaris.clientregistry.infrastructure.adapter.out.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataClientDomainConfigJpaRepository
    extends JpaRepository<ClientDomainConfigEntity, UUID> {

  Optional<ClientDomainConfigEntity> findByOauthClientId(UUID oauthClientId);

  Optional<ClientDomainConfigEntity> findByHostname(String hostname);
}
