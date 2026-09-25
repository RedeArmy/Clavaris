package com.clavaris.webhook.application.usecase.updatewebhookendpointurl;

import com.clavaris.webhook.domain.model.WebhookEndpoint;

@FunctionalInterface
public interface UpdateWebhookEndpointUrlUseCase {

  WebhookEndpoint handle(UpdateWebhookEndpointUrlCommand command);
}
