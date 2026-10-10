package com.clavaris.organization.application.usecase.updateorganizationprofile;

import com.clavaris.organization.domain.model.OrganizationLogo;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for {@link OrganizationLogo}: at most one per Organization. */
public interface OrganizationLogoRepository {

  Optional<OrganizationLogo> findByOrganizationId(UUID organizationId);

  /** Inserts the logo, or replaces the Organization's current one. */
  void save(OrganizationLogo logo);

  /** Removes the Organization's logo, if it has one. */
  void deleteByOrganizationId(UUID organizationId);
}
