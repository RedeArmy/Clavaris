package com.clavaris.organization.application.usecase.getorganizationprofiles;

import com.clavaris.organization.application.usecase.updateorganizationprofile.OrganizationProfileRepository;
import com.clavaris.organization.domain.model.OrganizationProfile;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

public class GetOrganizationProfilesService implements GetOrganizationProfilesUseCase {

  private final OrganizationProfileRepository profiles;

  public GetOrganizationProfilesService(final OrganizationProfileRepository profiles) {
    this.profiles = profiles;
  }

  @Override
  public Map<UUID, OrganizationProfile> handle(final Collection<UUID> organizationIds) {
    return organizationIds.isEmpty()
        ? Map.of()
        : profiles.findAllByOrganizationIds(organizationIds);
  }
}
