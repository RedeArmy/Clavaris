package com.clavaris.webhook.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.webhook.application.usecase.registerwebhookendpoint.RegisterWebhookEndpointResult;
import com.clavaris.webhook.domain.model.WebhookEndpoint;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RegisterWebhookEndpointResponseTest {

  @Test
  void toStringRedactsTheRawSigningSecret() {
    WebhookEndpoint endpoint =
        WebhookEndpoint.register(
            UUID.randomUUID(), "https://example.com", null, List.of("x"), "encrypted");
    RegisterWebhookEndpointResponse response =
        RegisterWebhookEndpointResponse.from(
            new RegisterWebhookEndpointResult(endpoint, "super-secret-raw-value"));

    String rendered = response.toString();

    assertThat(rendered).doesNotContain("super-secret-raw-value").contains("[REDACTED]");
  }
}
