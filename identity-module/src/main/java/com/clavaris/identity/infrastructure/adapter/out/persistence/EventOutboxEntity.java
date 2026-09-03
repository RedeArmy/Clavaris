package com.clavaris.identity.infrastructure.adapter.out.persistence;

import com.clavaris.common.infrastructure.adapter.out.persistence.AbstractEventOutboxEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA row mapping for {@code event_outbox} (ADR-0007 §1, data-model.md §2). Columns themselves live
 * on {@link AbstractEventOutboxEntity} — organization-module's own {@code
 * OrganizationEventOutboxEntity} shares the same seven-column shape against its own, separate
 * table; only the {@code @Table} name differs.
 */
@SuppressWarnings("PMD.ShortVariable")
@Entity
@Table(name = "event_outbox")
public class EventOutboxEntity extends AbstractEventOutboxEntity {

  protected EventOutboxEntity() {
    super();
  }

  @SuppressWarnings("java:S107")
  public EventOutboxEntity(
      final UUID id,
      final UUID organizationId,
      final String aggregateType,
      final UUID aggregateId,
      final String eventType,
      final String payload,
      final String traceId,
      final Instant occurredAt) {
    super(id, organizationId, aggregateType, aggregateId, eventType, payload, traceId, occurredAt);
  }
}
