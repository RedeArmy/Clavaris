package com.clavaris.organization.infrastructure.adapter.out.persistence;

import com.clavaris.organization.application.usecase.savesocialconnection.SocialConnectionSettingsRepository;
import com.clavaris.organization.domain.model.SocialConnectionSettings;
import com.clavaris.organization.domain.model.SocialProvider;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Implements the outbound port. {@code save} doubles as insert-or-update, because the id is always
 * a real, already-assigned UUID by the time it is called.
 */
@Repository
class JpaSocialConnectionSettingsRepository implements SocialConnectionSettingsRepository {

  private final SpringDataSocialConnectionSettingsJpaRepository settings;

  /* package */ JpaSocialConnectionSettingsRepository(
      final SpringDataSocialConnectionSettingsJpaRepository settings) {
    this.settings = settings;
  }

  @Override
  public Optional<SocialConnectionSettings> findByOrganizationIdAndProvider(
      final UUID organizationId, final SocialProvider provider) {
    return settings.findByOrganizationIdAndProvider(organizationId, provider).map(this::toDomain);
  }

  @Override
  public List<SocialConnectionSettings> findAllByOrganizationId(final UUID organizationId) {
    return settings.findAllByOrganizationId(organizationId).stream().map(this::toDomain).toList();
  }

  @Override
  public void save(final SocialConnectionSettings value) {
    settings.save(
        new SocialConnectionSettingsEntity(
            value.id(),
            value.organizationId(),
            value.provider(),
            value.blockEmailSubaddresses(),
            value.alwaysShowAccountSelector(),
            value.createdAt(),
            value.updatedAt()));
  }

  private SocialConnectionSettings toDomain(final SocialConnectionSettingsEntity entity) {
    return SocialConnectionSettings.reconstitute(
        entity.getId(),
        entity.getOrganizationId(),
        entity.getProvider(),
        entity.isBlockEmailSubaddresses(),
        entity.isAlwaysShowAccountSelector(),
        entity.getCreatedAt(),
        entity.getUpdatedAt());
  }
}
