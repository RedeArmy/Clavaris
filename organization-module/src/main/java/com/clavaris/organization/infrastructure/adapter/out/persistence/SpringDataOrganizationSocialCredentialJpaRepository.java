package com.clavaris.organization.infrastructure.adapter.out.persistence;

import com.clavaris.organization.domain.model.SocialProvider;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataOrganizationSocialCredentialJpaRepository
    extends JpaRepository<OrganizationSocialCredentialEntity, UUID> {

  Optional<OrganizationSocialCredentialEntity> findByOrganizationIdAndProvider(
      UUID organizationId, SocialProvider provider);

  List<OrganizationSocialCredentialEntity> findAllByOrganizationId(UUID organizationId);

  void deleteByOrganizationIdAndProvider(UUID organizationId, SocialProvider provider);
}
