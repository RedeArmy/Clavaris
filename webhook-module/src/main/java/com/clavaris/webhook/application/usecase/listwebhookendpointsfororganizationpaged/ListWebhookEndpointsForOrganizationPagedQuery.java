package com.clavaris.webhook.application.usecase.listwebhookendpointsfororganizationpaged;

import com.clavaris.common.domain.model.PageRequest;
import java.util.UUID;

public record ListWebhookEndpointsForOrganizationPagedQuery(
    UUID organizationId, PageRequest pageRequest) {}
