package com.clavaris.webhook.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA row mapping for {@code webhook_deliveries} (migration {@code V20260902120001}, ADR-0007 §2).
 * {@code status} is a plain {@code String}, not the domain's {@code WebhookDeliveryStatus} enum —
 * same convention as {@code SocialIdentityEntity.provider}: this entity never references a {@code
 * domain.model} type at all, {@link JpaWebhookDeliveryRepository} owns the conversion.
 */
@SuppressWarnings({"PMD.DataClass", "PMD.ShortVariable", "PMD.LongVariable"})
@Entity
@Table(name = "webhook_deliveries")
public class WebhookDeliveryEntity {

  @Id private UUID id;

  @Column(name = "endpoint_id", nullable = false)
  private UUID endpointId;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(name = "outbox_event_id", nullable = false)
  private UUID outboxEventId;

  @Column(name = "aggregate_type")
  private String aggregateType;

  @Column(name = "aggregate_id")
  private UUID aggregateId;

  @Column(name = "event_type")
  private String eventType;

  @Column(columnDefinition = "text")
  private String payload;

  @Column(nullable = false, length = 20)
  private String status;

  @Column(name = "attempt_count", nullable = false)
  private int attemptCount;

  @Column(name = "next_attempt_at")
  private Instant nextAttemptAt;

  @Column(name = "last_attempt_at")
  private Instant lastAttemptAt;

  @Column(name = "last_response_status")
  private Integer lastResponseStatus;

  @Column(name = "last_error", length = 500)
  private String lastError;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected WebhookDeliveryEntity() {}

  @SuppressWarnings({"java:S107", "PMD.ExcessiveParameterList"})
  public WebhookDeliveryEntity(
      final UUID id,
      final UUID endpointId,
      final UUID organizationId,
      final UUID outboxEventId,
      final String aggregateType,
      final UUID aggregateId,
      final String eventType,
      final String payload,
      final String status,
      final int attemptCount,
      final Instant nextAttemptAt,
      final Instant lastAttemptAt,
      final Integer lastResponseStatus,
      final String lastError,
      final Instant createdAt) {
    this.id = id;
    this.endpointId = endpointId;
    this.organizationId = organizationId;
    this.outboxEventId = outboxEventId;
    this.aggregateType = aggregateType;
    this.aggregateId = aggregateId;
    this.eventType = eventType;
    this.payload = payload;
    this.status = status;
    this.attemptCount = attemptCount;
    this.nextAttemptAt = nextAttemptAt;
    this.lastAttemptAt = lastAttemptAt;
    this.lastResponseStatus = lastResponseStatus;
    this.lastError = lastError;
    this.createdAt = createdAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getEndpointId() {
    return endpointId;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public UUID getOutboxEventId() {
    return outboxEventId;
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

  public String getStatus() {
    return status;
  }

  public int getAttemptCount() {
    return attemptCount;
  }

  public Instant getNextAttemptAt() {
    return nextAttemptAt;
  }

  public Instant getLastAttemptAt() {
    return lastAttemptAt;
  }

  public Integer getLastResponseStatus() {
    return lastResponseStatus;
  }

  public String getLastError() {
    return lastError;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
