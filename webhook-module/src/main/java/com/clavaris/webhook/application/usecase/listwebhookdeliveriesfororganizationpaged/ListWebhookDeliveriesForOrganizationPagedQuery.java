package com.clavaris.webhook.application.usecase.listwebhookdeliveriesfororganizationpaged;

import com.clavaris.common.domain.model.KeysetPageRequest;
import java.util.UUID;

public record ListWebhookDeliveriesForOrganizationPagedQuery(
    UUID organizationId, KeysetPageRequest pageRequest) {}
