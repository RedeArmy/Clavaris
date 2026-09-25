package com.clavaris.webhook.application.usecase.deletewebhookendpoint;

@FunctionalInterface
public interface DeleteWebhookEndpointUseCase {

  void handle(DeleteWebhookEndpointCommand command);
}
