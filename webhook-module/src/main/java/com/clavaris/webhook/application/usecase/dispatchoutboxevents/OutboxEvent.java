package com.clavaris.webhook.application.usecase.dispatchoutboxevents;

import java.time.Instant;
import java.util.UUID;

/**
 * A data-contract read of one row from either producer module's own outbox table (ADR-0007 §1's own
 * "depend on a data contract, not on the other modules' internal types" boundary) — deliberately
 * not identity-module's or organization-module's own outbox entity type; this module never depends
 * on either.
 *
 * <p>{@code traceId}: carried straight through from the producer's own {@code
 * AbstractEventOutboxEntity#getTraceId()} column — see that class's own Javadoc for how it got
 * there. {@code DispatchOutboxEventsService} copies it unchanged onto every {@code WebhookDelivery}
 * fanned out from this event; always optional, never assumed present.
 */
@SuppressWarnings("PMD.ShortVariable")
public record OutboxEvent(
    OutboxSource source,
    UUID id,
    UUID organizationId,
    String aggregateType,
    UUID aggregateId,
    String eventType,
    String payload,
    String traceId,
    Instant occurredAt) {}
