package com.clavaris.webhook.application.usecase.dispatchoutboxevents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.webhook.application.usecase.deliverpendingwebhooks.WebhookDeliveryRepository;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.WebhookEndpointRepository;
import com.clavaris.webhook.domain.model.WebhookDelivery;
import com.clavaris.webhook.domain.model.WebhookEndpoint;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DispatchOutboxEventsServiceTest {

  private final OutboxEventReader outboxEvents = mock(OutboxEventReader.class);
  private final WebhookEndpointRepository endpoints = mock(WebhookEndpointRepository.class);
  private final WebhookDeliveryRepository deliveries = mock(WebhookDeliveryRepository.class);
  private final DispatchOutboxEventsService service =
      new DispatchOutboxEventsService(outboxEvents, endpoints, deliveries, 200);

  @Test
  void schedulesOneDeliveryPerMatchingActiveEndpointAndMarksTheEventPublished() {
    UUID organizationId = UUID.randomUUID();
    OutboxEvent event =
        new OutboxEvent(
            OutboxSource.IDENTITY,
            UUID.randomUUID(),
            organizationId,
            "Account",
            UUID.randomUUID(),
            "account.created",
            "{}",
            "trace-abc123",
            Instant.now());
    when(outboxEvents.claimUnpublishedBatch(200)).thenReturn(List.of(event));
    WebhookEndpoint matchingEndpoint =
        WebhookEndpoint.register(
            organizationId, "https://example.com", null, List.of("account.created"), "secret");
    when(endpoints.findActiveByOrganizationIdAndEventType(organizationId, "account.created"))
        .thenReturn(List.of(matchingEndpoint));

    service.dispatchPendingEvents();

    ArgumentCaptor<WebhookDelivery> captor = ArgumentCaptor.forClass(WebhookDelivery.class);
    verify(deliveries).save(captor.capture());
    assertThat(captor.getValue().endpointId()).isEqualTo(matchingEndpoint.id());
    assertThat(captor.getValue().organizationId()).isEqualTo(organizationId);
    assertThat(captor.getValue().outboxEventId()).isEqualTo(event.id());
    assertThat(captor.getValue().traceId()).isEqualTo("trace-abc123");
    verify(outboxEvents).markPublished(event);
  }

  @Test
  void marksTheEventPublishedEvenWhenNoEndpointSubscribesToIt() {
    OutboxEvent event =
        new OutboxEvent(
            OutboxSource.ORGANIZATION,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "Workspace",
            UUID.randomUUID(),
            "workspace.created",
            "{}",
            null,
            Instant.now());
    when(outboxEvents.claimUnpublishedBatch(200)).thenReturn(List.of(event));
    when(endpoints.findActiveByOrganizationIdAndEventType(any(), any())).thenReturn(List.of());

    service.dispatchPendingEvents();

    verify(deliveries, never()).save(any());
    verify(outboxEvents).markPublished(event);
  }

  @Test
  void doesNothingWhenThereAreNoUnpublishedEvents() {
    when(outboxEvents.claimUnpublishedBatch(200)).thenReturn(List.of());

    service.dispatchPendingEvents();

    verify(deliveries, never()).save(any());
    verify(outboxEvents, never()).markPublished(any());
  }
}
