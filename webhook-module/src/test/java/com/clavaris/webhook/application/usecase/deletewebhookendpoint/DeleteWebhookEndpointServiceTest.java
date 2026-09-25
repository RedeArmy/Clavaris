package com.clavaris.webhook.application.usecase.deletewebhookendpoint;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

class DeleteWebhookEndpointServiceTest {

  private static final AuditActor ACTOR = AuditActor.platformClient("test-client");

  private final WebhookEndpointRepository endpoints = mock(WebhookEndpointRepository.class);
  private final AuditEventRecorder auditEvents = mock(AuditEventRecorder.class);
  private final DeleteWebhookEndpointService service =
      new DeleteWebhookEndpointService(endpoints, auditEvents);

  @Test
  void deletesAnInactiveEndpointAndAuditsIt() {
    WebhookEndpoint existing =
        WebhookEndpoint.register(
                UUID.randomUUID(), "https://example.com", null, List.of("x"), "secret")
            .deactivate();
    when(endpoints.findById(existing.id())).thenReturn(Optional.of(existing));

    service.handle(new DeleteWebhookEndpointCommand(existing.id(), ACTOR));

    verify(endpoints).delete(existing);
    verify(auditEvents)
        .write(
            ACTOR,
            "webhook_endpoint.deleted",
            "Organization",
            existing.organizationId().toString(),
            "deletedEndpointId=" + existing.id());
  }

  @Test
  void rejectsDeletingAnActiveEndpointAndNeverDeletes() {
    WebhookEndpoint existing =
        WebhookEndpoint.register(
            UUID.randomUUID(), "https://example.com", null, List.of("x"), "secret");
    when(endpoints.findById(existing.id())).thenReturn(Optional.of(existing));
    DeleteWebhookEndpointCommand command = new DeleteWebhookEndpointCommand(existing.id(), ACTOR);

    assertThatExceptionOfType(WebhookEndpointActiveException.class)
        .isThrownBy(() -> service.handle(command));
    verify(endpoints, never()).delete(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void rejectsDeletingAnUnknownEndpoint() {
    UUID unknownId = UUID.randomUUID();
    when(endpoints.findById(unknownId)).thenReturn(Optional.empty());
    DeleteWebhookEndpointCommand command = new DeleteWebhookEndpointCommand(unknownId, ACTOR);

    assertThatExceptionOfType(WebhookEndpointNotFoundException.class)
        .isThrownBy(() -> service.handle(command));
  }
}
