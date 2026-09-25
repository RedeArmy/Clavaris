package com.clavaris.webhook.application.usecase.updatewebhookendpointdescription;

import com.clavaris.webhook.domain.model.WebhookEndpoint;

@FunctionalInterface
public interface UpdateWebhookEndpointDescriptionUseCase {

  WebhookEndpoint handle(UpdateWebhookEndpointDescriptionCommand command);
}
