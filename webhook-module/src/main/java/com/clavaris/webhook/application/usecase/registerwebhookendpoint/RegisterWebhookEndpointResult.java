package com.clavaris.webhook.application.usecase.registerwebhookendpoint;

import com.clavaris.webhook.domain.model.WebhookEndpoint;

/**
 * @param rawSigningSecret shown exactly once, at creation — the caller (controller) is responsible
 *     for returning it in the HTTP response and never persisting or logging it itself; this
 *     record's own {@code toString()} is overridden so an accidental log statement elsewhere can't
 *     leak it regardless, same discipline as client-registry-module's own {@code
 *     RegisterOAuthClientResult}.
 */
public record RegisterWebhookEndpointResult(WebhookEndpoint endpoint, String rawSigningSecret) {

  @Override
  public String toString() {
    return "RegisterWebhookEndpointResult[endpoint=" + endpoint + ", rawSigningSecret=[REDACTED]]";
  }
}
