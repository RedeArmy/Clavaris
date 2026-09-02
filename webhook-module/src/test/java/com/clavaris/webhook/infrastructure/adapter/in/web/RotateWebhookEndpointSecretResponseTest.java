package com.clavaris.webhook.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.webhook.application.usecase.rotatewebhookendpointsecret.RotateWebhookEndpointSecretResult;
import com.clavaris.webhook.domain.model.WebhookEndpoint;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RotateWebhookEndpointSecretResponseTest {

  @Test
  void toStringRedactsTheRawNewSigningSecret() {
    WebhookEndpoint endpoint =
        WebhookEndpoint.register(
            UUID.randomUUID(), "https://example.com", null, List.of("x"), "encrypted");
    RotateWebhookEndpointSecretResponse response =
        RotateWebhookEndpointSecretResponse.from(
            new RotateWebhookEndpointSecretResult(endpoint, "super-secret-new-raw-value"));

    String rendered = response.toString();

    assertThat(rendered).doesNotContain("super-secret-new-raw-value").contains("[REDACTED]");
  }
}
