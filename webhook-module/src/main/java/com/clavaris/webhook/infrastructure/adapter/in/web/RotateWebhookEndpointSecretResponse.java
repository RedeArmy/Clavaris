package com.clavaris.webhook.infrastructure.adapter.in.web;

import com.clavaris.webhook.application.usecase.rotatewebhookendpointsecret.RotateWebhookEndpointSecretResult;

/**
 * @param signingSecret shown exactly once — same discipline as {@link
 *     RegisterWebhookEndpointResponse}.
 */
public record RotateWebhookEndpointSecretResponse(
    WebhookEndpointResponse endpoint, String signingSecret) {

  public static RotateWebhookEndpointSecretResponse from(
      final RotateWebhookEndpointSecretResult result) {
    return new RotateWebhookEndpointSecretResponse(
        WebhookEndpointResponse.from(result.endpoint()), result.rawNewSigningSecret());
  }

  @Override
  public String toString() {
    return "RotateWebhookEndpointSecretResponse[endpoint="
        + endpoint
        + ", signingSecret=[REDACTED]]";
  }
}
