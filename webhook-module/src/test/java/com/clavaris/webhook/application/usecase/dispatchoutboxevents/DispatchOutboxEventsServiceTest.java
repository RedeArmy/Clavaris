package com.clavaris.webhook.application.usecase.dispatchoutboxevents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
    when(endpoints.findActiveByOrganizationId(organizationId))
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
    when(endpoints.findActiveByOrganizationId(any())).thenReturn(List.of());

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

  // TD-PERF-005: the actual fix this row asked for — two events from the same Organization in one
  // claimed batch must reuse one findActiveByOrganizationId call, not issue it once per event.
  @Test
  void reusesOneOrganizationsEndpointListAcrossEveryEventFromItInTheSameBatch() {
    UUID organizationId = UUID.randomUUID();
    OutboxEvent firstEvent =
        new OutboxEvent(
            OutboxSource.IDENTITY,
            UUID.randomUUID(),
            organizationId,
            "Account",
            UUID.randomUUID(),
            "account.created",
            "{}",
            null,
            Instant.now());
    OutboxEvent secondEvent =
        new OutboxEvent(
            OutboxSource.IDENTITY,
            UUID.randomUUID(),
            organizationId,
            "Account",
            UUID.randomUUID(),
            "account.suspended",
            "{}",
            null,
            Instant.now());
    when(outboxEvents.claimUnpublishedBatch(200)).thenReturn(List.of(firstEvent, secondEvent));
    WebhookEndpoint subscribedToBoth =
        WebhookEndpoint.register(
            organizationId,
            "https://example.com",
            null,
            List.of("account.created", "account.suspended"),
            "secret");
    when(endpoints.findActiveByOrganizationId(organizationId))
        .thenReturn(List.of(subscribedToBoth));

    service.dispatchPendingEvents();

    verify(endpoints, times(1)).findActiveByOrganizationId(organizationId);
    verify(deliveries, times(2)).save(any());
    verify(outboxEvents).markPublished(firstEvent);
    verify(outboxEvents).markPublished(secondEvent);
  }
}
