package com.clavaris.organization.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA row mapping for this module's own {@code organization_event_outbox} (ADR-0007 §1, migration
 * {@code V20260826120000}) — identity-module's own {@code EventOutboxEntity} mirror,
 * module-prefixed on purpose: see that migration's own comment for why a same-named class here
 * would collide (Hibernate's own JPQL entity-name namespace, plain-simple-name-derived by default)
 * with identity-module's own identically-shaped class once both are on the same classpath. Same
 * PMD.ShortVariable rationale as {@code AccountEntity}/identity-module's own {@code
 * EventOutboxEntity}.
 */
@SuppressWarnings("PMD.ShortVariable")
@Entity
@Table(name = "organization_event_outbox")
public class OrganizationEventOutboxEntity {

  @Id private UUID id;

  @Column(name = "aggregate_type", nullable = false)
  private String aggregateType;

  @Column(name = "aggregate_id", nullable = false)
  private UUID aggregateId;

  @Column(name = "event_type", nullable = false)
  private String eventType;

  @Column(nullable = false, columnDefinition = "text")
  private String payload;

  @Column(name = "occurred_at", nullable = false)
  private Instant occurredAt;

  @Column(name = "published_at")
  private Instant publishedAt;

  protected OrganizationEventOutboxEntity() {}

  public OrganizationEventOutboxEntity(
      final UUID id,
      final String aggregateType,
      final UUID aggregateId,
      final String eventType,
      final String payload,
      final Instant occurredAt) {
    this.id = id;
    this.aggregateType = aggregateType;
    this.aggregateId = aggregateId;
    this.eventType = eventType;
    this.payload = payload;
    this.occurredAt = occurredAt;
    // publishedAt starts null — set only once a future dispatcher (webhook-module) delivers it.
  }
}
