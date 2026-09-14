package com.clavaris.clientregistry.application.usecase.listoauthclientspaged;

import com.clavaris.common.domain.model.KeysetPageRequest;
import java.util.UUID;

public record ListOAuthClientsPagedQuery(UUID organizationId, KeysetPageRequest pageRequest) {}
