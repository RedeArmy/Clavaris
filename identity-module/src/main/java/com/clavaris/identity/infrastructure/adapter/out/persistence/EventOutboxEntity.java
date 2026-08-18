package com.clavaris.identity.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA row mapping for {@code event_outbox} (ADR-0007 §1, data-model.md §2). See {@link
 * AccountEntity} for why PMD.ShortVariable is suppressed here too.
 */
@SuppressWarnings("PMD.ShortVariable")
@Entity
@Table(name = "event_outbox")
public class EventOutboxEntity {

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

  protected EventOutboxEntity() {}

  public EventOutboxEntity(
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
