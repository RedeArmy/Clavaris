package com.clavaris.clientregistry.infrastructure.adapter.out.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataClientDomainConfigJpaRepository
    extends JpaRepository<ClientDomainConfigEntity, UUID> {

  Optional<ClientDomainConfigEntity> findByOauthClientId(UUID oauthClientId);

  Optional<ClientDomainConfigEntity> findByHostname(String hostname);

  // PMD.LongVariable: verificationStatus names exactly what it is — same convention
  // SpringDataWorkspaceTeamRoleJpaRepository's own identical suppression documents.
  List<ClientDomainConfigEntity> findByVerificationStatus(
      @SuppressWarnings("PMD.LongVariable") String verificationStatus);
}
