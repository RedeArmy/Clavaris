package com.clavaris.webhook.application.usecase.activatewebhookendpoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.WebhookEndpointNotFoundException;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.WebhookEndpointRepository;
import com.clavaris.webhook.domain.model.WebhookEndpoint;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ActivateWebhookEndpointServiceTest {

  private static final AuditActor ACTOR = AuditActor.platformClient("test-client");

  private final WebhookEndpointRepository endpoints = mock(WebhookEndpointRepository.class);
  private final AuditEventRecorder auditEvents = mock(AuditEventRecorder.class);
  private final ActivateWebhookEndpointService service =
      new ActivateWebhookEndpointService(endpoints, auditEvents);

  @Test
  void reactivatesADeactivatedEndpointAndAuditsIt() {
    WebhookEndpoint deactivated =
        WebhookEndpoint.register(
                UUID.randomUUID(), "https://example.com", null, List.of("x"), "secret")
            .deactivate();
    when(endpoints.findById(deactivated.id())).thenReturn(Optional.of(deactivated));

    WebhookEndpoint result =
        service.handle(new ActivateWebhookEndpointCommand(deactivated.id(), ACTOR));

    assertThat(result.active()).isTrue();
    verify(endpoints).save(result);
    verify(auditEvents)
        .write(
            ACTOR,
            "webhook_endpoint.activated",
            "WebhookEndpoint",
            deactivated.id().toString(),
            null);
  }

  @Test
  void rejectsActivationOfAnUnknownEndpoint() {
    UUID unknownId = UUID.randomUUID();
    when(endpoints.findById(unknownId)).thenReturn(Optional.empty());
    ActivateWebhookEndpointCommand command = new ActivateWebhookEndpointCommand(unknownId, ACTOR);

    assertThatExceptionOfType(WebhookEndpointNotFoundException.class)
        .isThrownBy(() -> service.handle(command));
  }
}
