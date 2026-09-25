package com.clavaris.webhook.application.usecase.updatewebhookendpointdescription;

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

class UpdateWebhookEndpointDescriptionServiceTest {

  private static final AuditActor ACTOR = AuditActor.platformClient("test-client");

  private final WebhookEndpointRepository endpoints = mock(WebhookEndpointRepository.class);
  private final AuditEventRecorder auditEvents = mock(AuditEventRecorder.class);
  private final UpdateWebhookEndpointDescriptionService service =
      new UpdateWebhookEndpointDescriptionService(endpoints, auditEvents);

  @Test
  void updatesTheDescriptionAndAuditsIt() {
    WebhookEndpoint existing =
        WebhookEndpoint.register(
            UUID.randomUUID(), "https://example.com", "old desc", List.of("x"), "secret");
    when(endpoints.findById(existing.id())).thenReturn(Optional.of(existing));

    WebhookEndpoint result =
        service.handle(
            new UpdateWebhookEndpointDescriptionCommand(existing.id(), "new desc", ACTOR));

    assertThat(result.description()).isEqualTo("new desc");
    verify(endpoints).save(result);
    verify(auditEvents)
        .write(
            ACTOR,
            "webhook_endpoint.description_updated",
            "WebhookEndpoint",
            existing.id().toString(),
            null);
  }

  @Test
  void rejectsUpdatingAnUnknownEndpoint() {
    UUID unknownId = UUID.randomUUID();
    when(endpoints.findById(unknownId)).thenReturn(Optional.empty());
    UpdateWebhookEndpointDescriptionCommand command =
        new UpdateWebhookEndpointDescriptionCommand(unknownId, "desc", ACTOR);

    assertThatExceptionOfType(WebhookEndpointNotFoundException.class)
        .isThrownBy(() -> service.handle(command));
  }
}
