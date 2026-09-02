package com.clavaris.webhook.application.usecase.deactivatewebhookendpoint;

import com.clavaris.webhook.domain.model.WebhookEndpoint;

@FunctionalInterface
public interface DeactivateWebhookEndpointUseCase {

  WebhookEndpoint handle(DeactivateWebhookEndpointCommand command);
}
