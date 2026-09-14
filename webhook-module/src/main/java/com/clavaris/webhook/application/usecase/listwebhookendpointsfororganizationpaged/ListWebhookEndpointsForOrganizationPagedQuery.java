package com.clavaris.webhook.application.usecase.listwebhookendpointsfororganizationpaged;

import com.clavaris.common.domain.model.KeysetPageRequest;
import java.util.UUID;

public record ListWebhookEndpointsForOrganizationPagedQuery(
    UUID organizationId, KeysetPageRequest pageRequest) {}
