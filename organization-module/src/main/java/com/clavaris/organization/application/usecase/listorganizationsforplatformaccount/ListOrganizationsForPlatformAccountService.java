package com.clavaris.organization.application.usecase.listorganizationsforplatformaccount;

import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.domain.model.Organization;
import java.util.List;

public class ListOrganizationsForPlatformAccountService
    implements ListOrganizationsForPlatformAccountUseCase {

  private final OrganizationRepository organizations;

  public ListOrganizationsForPlatformAccountService(final OrganizationRepository organizations) {
    this.organizations = organizations;
  }

  @Override
  public List<Organization> handle(final ListOrganizationsForPlatformAccountQuery query) {
    return organizations.findAllOwnedBy(query.ownerPlatformAccountId());
  }
}
