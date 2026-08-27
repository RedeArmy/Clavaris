package com.clavaris.common.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import java.util.UUID;

/**
 * Shared column mapping for every module's own event-outbox table (ADR-0007 §1) — identity-module's
 * {@code EventOutboxEntity} and organization-module's {@code OrganizationEventOutboxEntity} both
 * extend this instead of duplicating the same seven columns (SonarCloud-flagged duplication on
 * TD-ARCH-007's own PR, closed by this extraction).
 *
 * <p>Deliberately {@code @MappedSuperclass}, not a shared {@code @Entity}/{@code @Table}: each
 * module still owns its own separate, independently-migrated table (own name, own migration file) —
 * this only removes the duplicated field/column boilerplate, not the intentional per-module table
 * isolation. See {@code OrganizationEventOutboxEntity}'s own Javadoc for why the tables themselves
 * must stay separate (a shared {@code event_outbox} name would collide the moment both modules'
 * Flyway migrations ran against the same schema).
 *
 * <p>No abstract method of its own — same PMD.AbstractClassWithoutAbstractMethod rationale as
 * identity-module's own {@code EmailPasswordForm}: the point of abstractness here is "never map
 * this on its own" ({@code @MappedSuperclass} has no {@code @Table}), not "force subclasses to
 * implement something."
 */
@MappedSuperclass
@SuppressWarnings({"PMD.ShortVariable", "PMD.AbstractClassWithoutAbstractMethod"})
public abstract class AbstractEventOutboxEntity {

  @Id protected UUID id;

  @Column(name = "aggregate_type", nullable = false)
  protected String aggregateType;

  @Column(name = "aggregate_id", nullable = false)
  protected UUID aggregateId;

  @Column(name = "event_type", nullable = false)
  protected String eventType;

  @Column(nullable = false, columnDefinition = "text")
  protected String payload;

  @Column(name = "occurred_at", nullable = false)
  protected Instant occurredAt;

  @Column(name = "published_at")
  protected Instant publishedAt;

  protected AbstractEventOutboxEntity() {}

  // java:S107: one constructor param per column is the correct shape for a plain persistence-
  // mapping data holder — same convention already accepted for AuditEventEntity/EventOutboxEntity.
  @SuppressWarnings("java:S107")
  protected AbstractEventOutboxEntity(
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
