package com.clavaris.organization.infrastructure.adapter.out.persistence;

import com.clavaris.organization.application.usecase.updateorganizationprofile.OrganizationProfileRepository;
import com.clavaris.organization.domain.model.OrganizationProfile;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class JpaOrganizationProfileRepository implements OrganizationProfileRepository {

  private final SpringDataOrganizationProfileJpaRepository profiles;

  /* package */ JpaOrganizationProfileRepository(
      final SpringDataOrganizationProfileJpaRepository profiles) {
    this.profiles = profiles;
  }

  @Override
  public Optional<OrganizationProfile> findByOrganizationId(final UUID organizationId) {
    return profiles.findById(organizationId).map(JpaOrganizationProfileRepository::toDomain);
  }

  @Override
  public Map<UUID, OrganizationProfile> findAllByOrganizationIds(
      final Collection<UUID> organizationIds) {
    final Map<UUID, OrganizationProfile> found = new HashMap<>();
    profiles
        .findAllById(organizationIds)
        .forEach(entity -> found.put(entity.getOrganizationId(), toDomain(entity)));
    return found;
  }

  @Override
  public void save(final OrganizationProfile profile) {
    profiles.save(
        new OrganizationProfileEntity(
            profile.organizationId(),
            profile.description().orElse(null),
            profile.applicationName().orElse(null),
            profile.brandColor().orElse(null),
            profile.logoUpdatedAt().orElse(null),
            profile.updatedAt()));
  }

  private static OrganizationProfile toDomain(final OrganizationProfileEntity entity) {
    return OrganizationProfile.reconstitute(
        entity.getOrganizationId(),
        entity.getDescription(),
        entity.getApplicationName(),
        entity.getBrandColor(),
        entity.getLogoUpdatedAt(),
        entity.getUpdatedAt());
  }
}
