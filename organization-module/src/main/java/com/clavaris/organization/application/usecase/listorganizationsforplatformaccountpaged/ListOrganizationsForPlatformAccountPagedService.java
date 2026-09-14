package com.clavaris.organization.application.usecase.listorganizationsforplatformaccountpaged;

import com.clavaris.common.domain.model.KeysetPage;
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
  public KeysetPage<Organization> handle(
      final ListOrganizationsForPlatformAccountPagedQuery query) {
    return organizations.findKeysetPageOwnedBy(query.ownerPlatformAccountId(), query.pageRequest());
  }
}
