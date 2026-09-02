package com.clavaris.webhook.application.usecase.rotatewebhookendpointsecret;

import com.clavaris.webhook.domain.model.WebhookEndpoint;

/**
 * @param rawNewSigningSecret shown exactly once — same redaction discipline as {@code
 *     RegisterWebhookEndpointResult}.
 */
@SuppressWarnings("PMD.LongVariable")
public record RotateWebhookEndpointSecretResult(
    WebhookEndpoint endpoint, String rawNewSigningSecret) {

  @Override
  public String toString() {
    return "RotateWebhookEndpointSecretResult[endpoint="
        + endpoint
        + ", rawNewSigningSecret=[REDACTED]]";
  }
}
