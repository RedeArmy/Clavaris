package com.clavaris.organization.application.usecase.getorganizationapikeys;

import java.util.Optional;
import java.util.UUID;

@FunctionalInterface
public interface GetOrganizationApiKeysUseCase {

  /** Empty when no Organization exists with the given id. */
  Optional<OrganizationApiKeys> handle(UUID organizationId);
}
