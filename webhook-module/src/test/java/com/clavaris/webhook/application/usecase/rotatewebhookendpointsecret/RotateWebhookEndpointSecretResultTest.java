package com.clavaris.webhook.application.usecase.rotatewebhookendpointsecret;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.webhook.domain.model.WebhookEndpoint;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RotateWebhookEndpointSecretResultTest {

  @Test
  void toStringRedactsTheRawNewSigningSecret() {
    WebhookEndpoint endpoint =
        WebhookEndpoint.register(
            UUID.randomUUID(), "https://example.com", null, List.of("x"), "encrypted");
    RotateWebhookEndpointSecretResult result =
        new RotateWebhookEndpointSecretResult(endpoint, "super-secret-new-raw-value");

    String rendered = result.toString();

    assertThat(rendered).doesNotContain("super-secret-new-raw-value").contains("[REDACTED]");
  }
}
