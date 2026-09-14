package com.clavaris.clientregistry.application.usecase.listorganizationclientspaged;

import com.clavaris.clientregistry.application.usecase.createorganizationclient.OrganizationClientRepository;
import com.clavaris.clientregistry.domain.model.OrganizationClient;
import com.clavaris.common.domain.model.Page;

// PMD.LongVariable: organizationClients names exactly what it is — same convention
// JpaOrganizationClientRepository's own identical suppression already establishes.
@SuppressWarnings("PMD.LongVariable")
public class ListOrganizationClientsPagedService implements ListOrganizationClientsPagedUseCase {

  private final OrganizationClientRepository organizationClients;

  public ListOrganizationClientsPagedService(
      final OrganizationClientRepository organizationClients) {
    this.organizationClients = organizationClients;
  }

  @Override
  public Page<OrganizationClient> handle(final ListOrganizationClientsPagedQuery query) {
    return organizationClients.findPageByOrganizationId(
        query.organizationId(), query.pageRequest());
  }
}
