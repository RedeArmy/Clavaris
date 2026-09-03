package com.clavaris.common.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import java.util.UUID;

/**
 * Shared column mapping for every module's own event-outbox table (ADR-0007 §1) — identity-module's
 * {@code EventOutboxEntity}, organization-module's {@code OrganizationEventOutboxEntity}, and
 * webhook-module's own read-side {@code IdentityOutboxRowEntity}/{@code
 * OrganizationOutboxRowEntity} all extend this instead of duplicating the same seven columns
 * (SonarCloud-flagged duplication, first on TD-ARCH-007's own PR for the two write-side entities,
 * then again on ADR-0007's own PR for webhook-module's two read-side ones — same root cause, same
 * fix, applied twice).
 *
 * <p>Deliberately {@code @MappedSuperclass}, not a shared {@code @Entity}/{@code @Table}: each
 * module still owns its own separate, independently-migrated table (own name, own migration file) —
 * this only removes the duplicated field/column boilerplate, not the intentional per-module table
 * isolation. See {@code OrganizationEventOutboxEntity}'s own Javadoc for why the tables themselves
 * must stay separate (a shared {@code event_outbox} name would collide the moment both modules'
 * Flyway migrations ran against the same schema).
 *
 * <p>{@code traceId} (end-to-end traceability, SDE-III webhook review): the distributed-tracing id
 * of the inbound request that produced this row, captured at write time from SLF4J's own MDC
 * (Spring Boot's Brave auto-configuration already populates the {@code "traceId"} MDC key on every
 * traced thread via {@code MDCScopeDecorator} — deliberately read from MDC rather than injecting a
 * {@code Tracer} bean directly, since {@code spring-boot-starter-zipkin} is only on {@code app}'s
 * own classpath and neither identity-module nor organization-module may take on that dependency
 * just to stamp this one column). {@code null} whenever the write happens off a traced thread (a
 * scheduled job, a test) — always optional, never assumed present.
 *
 * <p>No abstract method of its own — same PMD.AbstractClassWithoutAbstractMethod rationale as
 * identity-module's own {@code EmailPasswordForm}: the point of abstractness here is "never map
 * this on its own" ({@code @MappedSuperclass} has no {@code @Table}), not "force subclasses to
 * implement something." PMD.DataClass: eight plain accessors and no other behavior is exactly what
 * a shared persistence-mapping superclass looks like — real business behavior belongs on the domain
 * model this row eventually maps to (e.g. {@code WebhookDelivery}), never on the JPA mapping
 * itself, so this metric's usual "give it real methods" fix would be the wrong move here.
 */
@MappedSuperclass
@SuppressWarnings({"PMD.ShortVariable", "PMD.AbstractClassWithoutAbstractMethod", "PMD.DataClass"})
public abstract class AbstractEventOutboxEntity {

  @Id protected UUID id;

  // webhook-module dispatcher (ADR-0007 §1): the tenant-isolation boundary (ADR-0010) a fan-out
  // decision must key on. Captured explicitly at write time rather than parsed back out of
  // `payload` — not every event payload carries this field in the same JSON shape (identity-
  // module's own `OrganizationId` value type nests as `{"value":...}`; organization-module's own
  // events use a bare UUID; some, like WorkspaceMemberAddedEvent, didn't carry it at all until this
  // column existed), and a dispatcher that had to know each producer's own payload shape to route
  // safely would defeat the "depend on a data contract, not on internal types" boundary ADR-0007
  // itself already commits to.
  @Column(name = "organization_id", nullable = false)
  protected UUID organizationId;

  @Column(name = "aggregate_type", nullable = false)
  protected String aggregateType;

  @Column(name = "aggregate_id", nullable = false)
  protected UUID aggregateId;

  @Column(name = "event_type", nullable = false)
  protected String eventType;

  @Column(nullable = false, columnDefinition = "text")
  protected String payload;

  @Column(name = "trace_id", length = 32)
  protected String traceId;

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
      final UUID organizationId,
      final String aggregateType,
      final UUID aggregateId,
      final String eventType,
      final String payload,
      final String traceId,
      final Instant occurredAt) {
    this.id = id;
    this.organizationId = organizationId;
    this.aggregateType = aggregateType;
    this.aggregateId = aggregateId;
    this.eventType = eventType;
    this.payload = payload;
    this.traceId = traceId;
    this.occurredAt = occurredAt;
    // publishedAt starts null — set only once webhook-module's dispatcher delivers it.
  }

  // Getters only — neither identity-module's EventOutboxEntity nor organization-module's
  // OrganizationEventOutboxEntity ever reads a row back through JPA (both modules are write-only
  // producers), but webhook-module's own read-side entities (IdentityOutboxRowEntity/
  // OrganizationOutboxRowEntity, ADR-0007 §1) need every one of these to map a claimed row into an
  // OutboxEvent — adding them here instead of on each of those two subclasses is what actually
  // closed the SonarCloud-flagged duplication between them (same fields, same accessors, twice).
  public UUID getId() {
    return id;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public String getAggregateType() {
    return aggregateType;
  }

  public UUID getAggregateId() {
    return aggregateId;
  }

  public String getEventType() {
    return eventType;
  }

  public String getPayload() {
    return payload;
  }

  public String getTraceId() {
    return traceId;
  }

  public Instant getOccurredAt() {
    return occurredAt;
  }

  public Instant getPublishedAt() {
    return publishedAt;
  }
}
