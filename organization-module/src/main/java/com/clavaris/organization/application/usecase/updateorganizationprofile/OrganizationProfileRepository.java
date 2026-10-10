package com.clavaris.organization.application.usecase.updateorganizationprofile;

import com.clavaris.organization.domain.model.OrganizationProfile;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for {@link OrganizationProfile}. An Organization with no row simply has an empty
 * profile, so absence is an ordinary answer here, not an error.
 */
public interface OrganizationProfileRepository {

  Optional<OrganizationProfile> findByOrganizationId(UUID organizationId);

  /**
   * The profiles that exist among {@code organizationIds}, keyed by Organization id (an
   * Organization with no profile is simply absent from the map). One query for a whole dashboard
   * page.
   */
  Map<UUID, OrganizationProfile> findAllByOrganizationIds(Collection<UUID> organizationIds);

  /** Inserts or updates the profile of its Organization. */
  void save(OrganizationProfile profile);
}
