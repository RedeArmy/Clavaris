package com.clavaris.webhook.application.usecase.registerwebhookendpoint;

@FunctionalInterface
public interface RegisterWebhookEndpointUseCase {

  RegisterWebhookEndpointResult handle(RegisterWebhookEndpointCommand command);
}
