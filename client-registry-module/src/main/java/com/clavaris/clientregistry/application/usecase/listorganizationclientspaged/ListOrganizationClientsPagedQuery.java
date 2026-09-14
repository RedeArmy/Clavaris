package com.clavaris.clientregistry.application.usecase.listorganizationclientspaged;

import com.clavaris.common.domain.model.PageRequest;
import java.util.UUID;

public record ListOrganizationClientsPagedQuery(UUID organizationId, PageRequest pageRequest) {}
