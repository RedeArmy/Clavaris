package com.clavaris.webhook.application.usecase.registerwebhookendpoint;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.webhook.domain.model.WebhookEndpoint;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RegisterWebhookEndpointResultTest {

  @Test
  void toStringRedactsTheRawSigningSecret() {
    WebhookEndpoint endpoint =
        WebhookEndpoint.register(
            UUID.randomUUID(), "https://example.com", null, List.of("x"), "encrypted");
    RegisterWebhookEndpointResult result =
        new RegisterWebhookEndpointResult(endpoint, "super-secret-raw-value");

    String rendered = result.toString();

    assertThat(rendered).doesNotContain("super-secret-raw-value").contains("[REDACTED]");
  }
}
