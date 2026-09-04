package com.clavaris.clientregistry.application.usecase.listorganizationclients;

import com.clavaris.clientregistry.application.usecase.createorganizationclient.OrganizationClientRepository;
import com.clavaris.clientregistry.domain.model.OrganizationClient;
import java.util.List;
import java.util.UUID;

/**
 * Read-only — same "an empty list is a valid, safe answer either way" posture {@code
 * ListOrganizationSocialCredentialsService} already establishes.
 */
@SuppressWarnings("PMD.LongVariable")
public class ListOrganizationClientsService implements ListOrganizationClientsUseCase {

  private final OrganizationClientRepository organizationClients;

  public ListOrganizationClientsService(final OrganizationClientRepository organizationClients) {
    this.organizationClients = organizationClients;
  }

  @Override
  public List<OrganizationClient> handle(final UUID organizationId) {
    return organizationClients.findAllByOrganizationId(organizationId);
  }
}
