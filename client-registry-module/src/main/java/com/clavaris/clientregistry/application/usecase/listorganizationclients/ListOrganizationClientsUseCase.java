package com.clavaris.clientregistry.application.usecase.listorganizationclients;

import com.clavaris.clientregistry.domain.model.OrganizationClient;
import java.util.List;
import java.util.UUID;

@FunctionalInterface
public interface ListOrganizationClientsUseCase {

  List<OrganizationClient> handle(UUID organizationId);
}
