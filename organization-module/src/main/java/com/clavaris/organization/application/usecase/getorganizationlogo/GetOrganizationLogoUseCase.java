package com.clavaris.organization.application.usecase.getorganizationlogo;

import com.clavaris.organization.domain.model.OrganizationLogo;
import java.util.Optional;
import java.util.UUID;

/**
 * The Organization's logo image, if it has one. Public: it is shown to the application's people.
 */
@FunctionalInterface
public interface GetOrganizationLogoUseCase {

  Optional<OrganizationLogo> handle(UUID organizationId);
}
