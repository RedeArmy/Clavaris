package com.clavaris.webhook.application.usecase.updatewebhookendpointeventtypes;

import com.clavaris.webhook.domain.model.WebhookEndpoint;

@FunctionalInterface
public interface UpdateWebhookEndpointEventTypesUseCase {

  WebhookEndpoint handle(UpdateWebhookEndpointEventTypesCommand command);
}
