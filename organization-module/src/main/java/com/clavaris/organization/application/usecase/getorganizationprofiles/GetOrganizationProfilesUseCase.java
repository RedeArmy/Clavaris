package com.clavaris.organization.application.usecase.getorganizationprofiles;

import com.clavaris.organization.domain.model.OrganizationProfile;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * The profiles of several Organizations in one go, for the dashboard's cards and for the
 * consumer-facing pages and emails that brand themselves with an Organization's application name,
 * colour and logo. An Organization with no profile is absent from the map.
 */
@FunctionalInterface
public interface GetOrganizationProfilesUseCase {

  Map<UUID, OrganizationProfile> handle(Collection<UUID> organizationIds);
}
