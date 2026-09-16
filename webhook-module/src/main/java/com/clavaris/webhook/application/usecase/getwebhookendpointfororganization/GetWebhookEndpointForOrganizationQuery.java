package com.clavaris.webhook.application.usecase.getwebhookendpointfororganization;

import java.util.UUID;

public record GetWebhookEndpointForOrganizationQuery(UUID organizationId, UUID endpointId) {}
