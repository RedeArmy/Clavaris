package com.clavaris.webhook.infrastructure.adapter.in.web;

import com.clavaris.webhook.application.usecase.registerwebhookendpoint.RegisterWebhookEndpointResult;

/**
 * @param signingSecret shown exactly once, at creation — same "shown once" convention as {@code
 *     RegisterOAuthClientResponse.clientSecret}. {@code toString()} is overridden regardless, same
 *     defensive reason that class's own override exists.
 */
public record RegisterWebhookEndpointResponse(
    WebhookEndpointResponse endpoint, String signingSecret) {

  public static RegisterWebhookEndpointResponse from(final RegisterWebhookEndpointResult result) {
    return new RegisterWebhookEndpointResponse(
        WebhookEndpointResponse.from(result.endpoint()), result.rawSigningSecret());
  }

  @Override
  public String toString() {
    return "RegisterWebhookEndpointResponse[endpoint=" + endpoint + ", signingSecret=[REDACTED]]";
  }
}
