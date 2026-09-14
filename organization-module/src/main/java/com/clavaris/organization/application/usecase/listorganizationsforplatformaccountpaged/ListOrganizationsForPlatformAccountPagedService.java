package com.clavaris.organization.application.usecase.listorganizationsforplatformaccountpaged;

import com.clavaris.common.domain.model.Page;
import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.domain.model.Organization;

public class ListOrganizationsForPlatformAccountPagedService
    implements ListOrganizationsForPlatformAccountPagedUseCase {

  private final OrganizationRepository organizations;

  public ListOrganizationsForPlatformAccountPagedService(
      final OrganizationRepository organizations) {
    this.organizations = organizations;
  }

  @Override
  public Page<Organization> handle(final ListOrganizationsForPlatformAccountPagedQuery query) {
    return organizations.findPageOwnedBy(query.ownerPlatformAccountId(), query.pageRequest());
  }
}
