package com.clavaris.clientregistry.infrastructure.adapter.out.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataClientDomainConfigJpaRepository
    extends JpaRepository<ClientDomainConfigEntity, UUID> {

  Optional<ClientDomainConfigEntity> findByOauthClientId(UUID oauthClientId);

  Optional<ClientDomainConfigEntity> findByHostname(String hostname);

  // PMD.LongVariable: verificationStatus names exactly what it is — same convention
  // SpringDataWorkspaceTeamRoleJpaRepository's own identical suppression documents.
  List<ClientDomainConfigEntity> findByVerificationStatus(String verificationStatus);

  // PMD.LongVariable: same rationale as findByVerificationStatus above. An EXISTS over the owning
  // client rather than a join, so a client-less (orphaned) config can never count for an
  // Organization it does not belong to.
  @Query(
      """
      SELECT COUNT(c) > 0 FROM ClientDomainConfigEntity c
      WHERE c.verificationStatus = :verificationStatus
        AND c.embeddingOrigin IS NOT NULL
        AND EXISTS (SELECT 1 FROM OAuthClientEntity o
                    WHERE o.id = c.oauthClientId AND o.organizationId = :organizationId)
      """)
  boolean existsEmbeddingByOrganizationId(
      @Param("organizationId") UUID organizationId,
      @Param("verificationStatus") String verificationStatus);
}
