package com.clavaris.organization.application.usecase.getorganizationlogo;

import com.clavaris.organization.application.usecase.updateorganizationprofile.OrganizationLogoRepository;
import com.clavaris.organization.domain.model.OrganizationLogo;
import java.util.Optional;
import java.util.UUID;

public class GetOrganizationLogoService implements GetOrganizationLogoUseCase {

  private final OrganizationLogoRepository logos;

  public GetOrganizationLogoService(final OrganizationLogoRepository logos) {
    this.logos = logos;
  }

  @Override
  public Optional<OrganizationLogo> handle(final UUID organizationId) {
    return logos.findByOrganizationId(organizationId);
  }
}
