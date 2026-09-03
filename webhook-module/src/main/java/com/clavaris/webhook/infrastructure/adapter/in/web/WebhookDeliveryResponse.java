package com.clavaris.webhook.infrastructure.adapter.in.web;

import com.clavaris.webhook.domain.model.WebhookDelivery;
import java.time.Instant;
import java.util.UUID;

@SuppressWarnings({"PMD.ShortVariable", "PMD.LongVariable"})
public record WebhookDeliveryResponse(
    UUID id,
    UUID endpointId,
    UUID outboxEventId,
    String aggregateType,
    UUID aggregateId,
    String eventType,
    String payload,
    String traceId,
    String status,
    int attemptCount,
    Instant nextAttemptAt,
    Instant lastAttemptAt,
    Integer lastResponseStatus,
    String lastError,
    Instant createdAt) {

  public static WebhookDeliveryResponse from(final WebhookDelivery delivery) {
    return new WebhookDeliveryResponse(
        delivery.id(),
        delivery.endpointId(),
        delivery.outboxEventId(),
        delivery.aggregateType(),
        delivery.aggregateId(),
        delivery.eventType(),
        delivery.payload(),
        delivery.traceId(),
        delivery.status().name(),
        delivery.attemptCount(),
        delivery.nextAttemptAt(),
        delivery.lastAttemptAt(),
        delivery.lastResponseStatus(),
        delivery.lastError(),
        delivery.createdAt());
  }
}
