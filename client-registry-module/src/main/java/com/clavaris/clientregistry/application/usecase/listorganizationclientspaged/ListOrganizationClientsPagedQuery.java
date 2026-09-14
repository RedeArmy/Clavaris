package com.clavaris.clientregistry.application.usecase.listorganizationclientspaged;

import com.clavaris.common.domain.model.KeysetPageRequest;
import java.util.UUID;

public record ListOrganizationClientsPagedQuery(
    UUID organizationId, KeysetPageRequest pageRequest) {}
