package com.clavaris.clientregistry.application.usecase.listorganizationclientspaged;

import com.clavaris.clientregistry.application.usecase.createorganizationclient.OrganizationClientRepository;
import com.clavaris.clientregistry.domain.model.OrganizationClient;
import com.clavaris.common.domain.model.KeysetPage;

public class ListOrganizationClientsPagedService implements ListOrganizationClientsPagedUseCase {

  private final OrganizationClientRepository organizationClients;

  public ListOrganizationClientsPagedService(
      final OrganizationClientRepository organizationClients) {
    this.organizationClients = organizationClients;
  }

  @Override
  public KeysetPage<OrganizationClient> handle(final ListOrganizationClientsPagedQuery query) {
    return organizationClients.findKeysetPageByOrganizationId(
        query.organizationId(), query.pageRequest());
  }
}
