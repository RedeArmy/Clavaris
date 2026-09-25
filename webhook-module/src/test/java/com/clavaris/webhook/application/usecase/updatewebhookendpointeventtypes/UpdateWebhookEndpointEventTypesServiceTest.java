package com.clavaris.webhook.application.usecase.updatewebhookendpointeventtypes;

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

class UpdateWebhookEndpointEventTypesServiceTest {

  private static final AuditActor ACTOR = AuditActor.platformClient("test-client");

  private final WebhookEndpointRepository endpoints = mock(WebhookEndpointRepository.class);
  private final AuditEventRecorder auditEvents = mock(AuditEventRecorder.class);
  private final UpdateWebhookEndpointEventTypesService service =
      new UpdateWebhookEndpointEventTypesService(endpoints, auditEvents);

  @Test
  void updatesTheSubscribedEventTypesAndAuditsIt() {
    WebhookEndpoint existing =
        WebhookEndpoint.register(
            UUID.randomUUID(), "https://example.com", null, List.of("account.created"), "secret");
    when(endpoints.findById(existing.id())).thenReturn(Optional.of(existing));

    WebhookEndpoint result =
        service.handle(
            new UpdateWebhookEndpointEventTypesCommand(
                existing.id(), List.of("account.deleted", "workspace.created"), ACTOR));

    assertThat(result.subscribedEventTypes())
        .containsExactlyInAnyOrder("account.deleted", "workspace.created");
    verify(endpoints).save(result);
    verify(auditEvents)
        .write(
            ACTOR,
            "webhook_endpoint.event_types_updated",
            "WebhookEndpoint",
            existing.id().toString(),
            null);
  }

  @Test
  void allowsUpdatingToTheAllEventsWildcard() {
    WebhookEndpoint existing =
        WebhookEndpoint.register(
            UUID.randomUUID(), "https://example.com", null, List.of("account.created"), "secret");
    when(endpoints.findById(existing.id())).thenReturn(Optional.of(existing));

    WebhookEndpoint result =
        service.handle(
            new UpdateWebhookEndpointEventTypesCommand(
                existing.id(), List.of(WebhookEndpoint.ALL_EVENTS_WILDCARD), ACTOR));

    assertThat(result.subscribesTo("some_future_event_type.not_invented_yet")).isTrue();
  }

  @Test
  void rejectsUpdatingAnUnknownEndpoint() {
    UUID unknownId = UUID.randomUUID();
    when(endpoints.findById(unknownId)).thenReturn(Optional.empty());
    UpdateWebhookEndpointEventTypesCommand command =
        new UpdateWebhookEndpointEventTypesCommand(unknownId, List.of("x"), ACTOR);

    assertThatExceptionOfType(WebhookEndpointNotFoundException.class)
        .isThrownBy(() -> service.handle(command));
  }
}
