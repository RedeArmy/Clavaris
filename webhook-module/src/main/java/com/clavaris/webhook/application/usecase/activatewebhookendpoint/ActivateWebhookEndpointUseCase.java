package com.clavaris.webhook.application.usecase.activatewebhookendpoint;

import com.clavaris.webhook.domain.model.WebhookEndpoint;

@FunctionalInterface
public interface ActivateWebhookEndpointUseCase {

  WebhookEndpoint handle(ActivateWebhookEndpointCommand command);
}
