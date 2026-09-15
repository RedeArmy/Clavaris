package com.clavaris.webhook.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * ADR-0007 §2: one fan-out target for one outbox event — {@code (outboxEventId, endpointId)} is a
 * compound key (one endpoint gets at most one row per event), enforced at the persistence layer.
 *
 * <p>{@code payload} is a snapshot captured at fan-out time, not a live read of the source outbox
 * row — a retry hours later must send the exact bytes that were signed, never one that could have
 * drifted. {@code organizationId} is denormalized from {@link WebhookEndpoint} so retention/listing
 * queries don't need to join back for every row.
 *
 * <p>{@code traceId}: the distributed-tracing id of the original inbound request, captured once at
 * {@code schedule}-time and carried unchanged through every later mutation — {@code null} for a row
 * whose source predates this column or wasn't written inside a traced request.
 *
 * <p>PMD suppressions below: coding-standards.md §3a.
 */
@SuppressWarnings({
  "PMD.DataClass",
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.ShortVariable",
  "PMD.ShortMethodName",
  "PMD.TooManyMethods",
  "PMD.LongVariable"
})
public final class WebhookDelivery {

  private final UUID id;
  private final UUID endpointId;
  private final UUID organizationId;
  private final UUID outboxEventId;
  private final String aggregateType;
  private final UUID aggregateId;
  private final String eventType;
  private final String payload;
  private final String traceId;
  private final WebhookDeliveryStatus status;
  private final int attemptCount;
  private final Instant nextAttemptAt;
  private final Instant lastAttemptAt;
  private final Integer lastResponseStatus;
  private final String lastError;
  private final Instant createdAt;

  @SuppressWarnings("java:S107")
  private WebhookDelivery(
      final UUID id,
      final UUID endpointId,
      final UUID organizationId,
      final UUID outboxEventId,
      final String aggregateType,
      final UUID aggregateId,
      final String eventType,
      final String payload,
      final String traceId,
      final WebhookDeliveryStatus status,
      final int attemptCount,
      final Instant nextAttemptAt,
      final Instant lastAttemptAt,
      final Integer lastResponseStatus,
      final String lastError,
      final Instant createdAt) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.endpointId = Objects.requireNonNull(endpointId, "endpointId must not be null");
    this.organizationId = Objects.requireNonNull(organizationId, "organizationId must not be null");
    this.outboxEventId = Objects.requireNonNull(outboxEventId, "outboxEventId must not be null");
    this.aggregateType = aggregateType;
    this.aggregateId = aggregateId;
    this.eventType = eventType;
    this.payload = payload;
    this.traceId = traceId;
    this.status = Objects.requireNonNull(status, "status must not be null");
    this.attemptCount = attemptCount;
    this.nextAttemptAt = nextAttemptAt;
    this.lastAttemptAt = lastAttemptAt;
    this.lastResponseStatus = lastResponseStatus;
    this.lastError = lastError;
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
  }

  @SuppressWarnings("java:S107")
  public static WebhookDelivery schedule(
      final UUID endpointId,
      final UUID organizationId,
      final UUID outboxEventId,
      final String aggregateType,
      final UUID aggregateId,
      final String eventType,
      final String payload,
      final String traceId) {
    final Instant now = Instant.now();
    return new WebhookDelivery(
        UUID.randomUUID(),
        endpointId,
        organizationId,
        outboxEventId,
        aggregateType,
        aggregateId,
        eventType,
        payload,
        traceId,
        WebhookDeliveryStatus.PENDING,
        0,
        now,
        null,
        null,
        null,
        now);
  }

  /** Rehydrates an existing row — preserves the real persisted {@code id}/{@code createdAt}. */
  @SuppressWarnings({"java:S107", "PMD.ExcessiveParameterList"})
  public static WebhookDelivery reconstitute(
      final UUID id,
      final UUID endpointId,
      final UUID organizationId,
      final UUID outboxEventId,
      final String aggregateType,
      final UUID aggregateId,
      final String eventType,
      final String payload,
      final String traceId,
      final WebhookDeliveryStatus status,
      final int attemptCount,
      final Instant nextAttemptAt,
      final Instant lastAttemptAt,
      final Integer lastResponseStatus,
      final String lastError,
      final Instant createdAt) {
    return new WebhookDelivery(
        id,
        endpointId,
        organizationId,
        outboxEventId,
        aggregateType,
        aggregateId,
        eventType,
        payload,
        traceId,
        status,
        attemptCount,
        nextAttemptAt,
        lastAttemptAt,
        lastResponseStatus,
        lastError,
        createdAt);
  }

  /**
   * Bumps {@code nextAttemptAt} into the future without recording an attempt — the lease a claiming
   * dispatcher takes before the real HTTP call, so a second concurrent tick can't pick up the same
   * row (see {@code DeliverPendingWebhooksService}).
   */
  public WebhookDelivery lease(final Instant leaseUntil) {
    return new WebhookDelivery(
        id,
        endpointId,
        organizationId,
        outboxEventId,
        aggregateType,
        aggregateId,
        eventType,
        payload,
        traceId,
        status,
        attemptCount,
        leaseUntil,
        lastAttemptAt,
        lastResponseStatus,
        lastError,
        createdAt);
  }

  public WebhookDelivery recordSuccess(final int responseStatus, final Instant now) {
    return new WebhookDelivery(
        id,
        endpointId,
        organizationId,
        outboxEventId,
        aggregateType,
        aggregateId,
        eventType,
        payload,
        traceId,
        WebhookDeliveryStatus.SUCCEEDED,
        attemptCount + 1,
        null,
        now,
        responseStatus,
        null,
        createdAt);
  }

  /**
   * @param nextAttemptAt when the next retry is due, or {@code null} to mark {@link
   *     WebhookDeliveryStatus#EXHAUSTED} — the caller owns the retry-count policy and backoff math
   *     ({@code WebhookRetrySchedule}).
   */
  public WebhookDelivery recordFailure(
      final Integer responseStatus,
      final String error,
      final Instant now,
      final Instant nextAttemptAt) {
    return new WebhookDelivery(
        id,
        endpointId,
        organizationId,
        outboxEventId,
        aggregateType,
        aggregateId,
        eventType,
        payload,
        traceId,
        nextAttemptAt == null ? WebhookDeliveryStatus.EXHAUSTED : WebhookDeliveryStatus.FAILED,
        attemptCount + 1,
        nextAttemptAt,
        now,
        responseStatus,
        error,
        createdAt);
  }

  /**
   * BR-WEBHOOK-03: an operator manually re-triggers a terminal ({@code SUCCEEDED}/{@code
   * EXHAUSTED}) delivery — back to {@code PENDING}, due immediately, next claim batch picks it up
   * the same way any ordinary retry would. {@code attemptCount}'s own running total is left
   * untouched — it's a lifetime counter, not reset by a manual nudge.
   */
  public WebhookDelivery resetForReplay(final Instant now) {
    return new WebhookDelivery(
        id,
        endpointId,
        organizationId,
        outboxEventId,
        aggregateType,
        aggregateId,
        eventType,
        payload,
        traceId,
        WebhookDeliveryStatus.PENDING,
        attemptCount,
        now,
        lastAttemptAt,
        lastResponseStatus,
        lastError,
        createdAt);
  }

  public UUID id() {
    return id;
  }

  public UUID endpointId() {
    return endpointId;
  }

  public UUID organizationId() {
    return organizationId;
  }

  public UUID outboxEventId() {
    return outboxEventId;
  }

  public String aggregateType() {
    return aggregateType;
  }

  public UUID aggregateId() {
    return aggregateId;
  }

  public String eventType() {
    return eventType;
  }

  public String payload() {
    return payload;
  }

  public String traceId() {
    return traceId;
  }

  public WebhookDeliveryStatus status() {
    return status;
  }

  public int attemptCount() {
    return attemptCount;
  }

  public Instant nextAttemptAt() {
    return nextAttemptAt;
  }

  public Instant lastAttemptAt() {
    return lastAttemptAt;
  }

  public Integer lastResponseStatus() {
    return lastResponseStatus;
  }

  public String lastError() {
    return lastError;
  }

  public Instant createdAt() {
    return createdAt;
  }
}
