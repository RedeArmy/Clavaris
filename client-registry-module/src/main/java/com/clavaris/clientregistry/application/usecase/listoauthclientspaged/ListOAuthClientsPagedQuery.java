package com.clavaris.clientregistry.application.usecase.listoauthclientspaged;

import com.clavaris.common.domain.model.PageRequest;
import java.util.UUID;

public record ListOAuthClientsPagedQuery(UUID organizationId, PageRequest pageRequest) {}
