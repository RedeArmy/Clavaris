package com.clavaris.organization.infrastructure.adapter.out.persistence;

import com.clavaris.organization.domain.model.SocialProvider;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataSocialConnectionSettingsJpaRepository
    extends JpaRepository<SocialConnectionSettingsEntity, UUID> {

  Optional<SocialConnectionSettingsEntity> findByOrganizationIdAndProvider(
      UUID organizationId, SocialProvider provider);

  List<SocialConnectionSettingsEntity> findAllByOrganizationId(UUID organizationId);
}
