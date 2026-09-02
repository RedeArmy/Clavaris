package com.clavaris.webhook.application.usecase.rotatewebhookendpointsecret;

@FunctionalInterface
public interface RotateWebhookEndpointSecretUseCase {

  RotateWebhookEndpointSecretResult handle(RotateWebhookEndpointSecretCommand command);
}
