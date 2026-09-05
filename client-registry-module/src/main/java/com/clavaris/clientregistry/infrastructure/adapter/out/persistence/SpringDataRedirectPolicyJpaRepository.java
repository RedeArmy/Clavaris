package com.clavaris.clientregistry.infrastructure.adapter.out.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataRedirectPolicyJpaRepository extends JpaRepository<RedirectPolicyEntity, UUID> {

  Optional<RedirectPolicyEntity> findByOauthClientId(UUID oauthClientId);
}
