package com.clavaris.webhook.application.usecase.dispatchoutboxevents;

import java.time.Instant;
import java.util.UUID;

/**
 * A data-contract read of one row from either producer module's own outbox table (ADR-0007 §1's own
 * "depend on a data contract, not on the other modules' internal types" boundary) — deliberately
 * not identity-module's or organization-module's own outbox entity type; this module never depends
 * on either.
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
    Instant occurredAt) {}
