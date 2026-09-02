package com.clavaris.webhook.application.usecase.rotatewebhookendpointsecret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.WebhookEndpointNotFoundException;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.WebhookEndpointRepository;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.WebhookSigningSecretCipher;
import com.clavaris.webhook.domain.model.WebhookEndpoint;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RotateWebhookEndpointSecretServiceTest {

  private static final AuditActor ACTOR = AuditActor.platformClient("test-client");
  private static final Duration OVERLAP = Duration.ofHours(24);

  private final WebhookEndpointRepository endpoints = mock(WebhookEndpointRepository.class);
  private final WebhookSigningSecretCipher cipher = mock(WebhookSigningSecretCipher.class);
  private final AuditEventRecorder auditEvents = mock(AuditEventRecorder.class);
  private final RotateWebhookEndpointSecretService service =
      new RotateWebhookEndpointSecretService(endpoints, cipher, auditEvents, OVERLAP);

  @Test
  void rotatesTheSecretKeepingTheOldOneAsPreviousAndAuditsIt() {
    WebhookEndpoint existing =
        WebhookEndpoint.register(
            UUID.randomUUID(), "https://example.com", null, List.of("x"), "old-encrypted");
    when(endpoints.findById(existing.id())).thenReturn(Optional.of(existing));
    when(cipher.encrypt(any())).thenReturn("new-encrypted");

    RotateWebhookEndpointSecretResult result =
        service.handle(new RotateWebhookEndpointSecretCommand(existing.id(), ACTOR));

    assertThat(result.endpoint().currentSecretEncrypted()).isEqualTo("new-encrypted");
    assertThat(result.endpoint().previousSecretEncrypted()).isEqualTo("old-encrypted");
    assertThat(result.rawNewSigningSecret()).isNotBlank();
    verify(endpoints).save(result.endpoint());
    verify(auditEvents)
        .write(
            ACTOR,
            "webhook_endpoint.secret_rotated",
            "WebhookEndpoint",
            existing.id().toString(),
            null);
  }

  @Test
  void rejectsRotationForAnUnknownEndpoint() {
    UUID unknownId = UUID.randomUUID();
    when(endpoints.findById(unknownId)).thenReturn(Optional.empty());
    RotateWebhookEndpointSecretCommand command =
        new RotateWebhookEndpointSecretCommand(unknownId, ACTOR);

    assertThatExceptionOfType(WebhookEndpointNotFoundException.class)
        .isThrownBy(() -> service.handle(command));
  }
}
