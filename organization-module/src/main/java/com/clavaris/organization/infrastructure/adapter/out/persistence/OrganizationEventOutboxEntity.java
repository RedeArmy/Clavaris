package com.clavaris.organization.infrastructure.adapter.out.persistence;

import com.clavaris.common.infrastructure.adapter.out.persistence.AbstractEventOutboxEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA row mapping for this module's own {@code organization_event_outbox} (ADR-0007 §1, migration
 * {@code V20260826120000}) — columns themselves live on {@link AbstractEventOutboxEntity}, shared
 * with identity-module's own {@code EventOutboxEntity}. Module-prefixed class name, own
 * {@code @Table}: see that migration's own comment for why a same-named table here would collide
 * with identity-module's own {@code event_outbox} (Flyway's default {@code classpath:db/migration}
 * location merges every module's migrations into one shared Postgres schema in the real running
 * app) — this class only shares column boilerplate with identity-module's, never the table itself.
 */
@SuppressWarnings("PMD.ShortVariable")
@Entity
@Table(name = "organization_event_outbox")
public class OrganizationEventOutboxEntity extends AbstractEventOutboxEntity {

  protected OrganizationEventOutboxEntity() {
    super();
  }

  @SuppressWarnings("java:S107")
  public OrganizationEventOutboxEntity(
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
