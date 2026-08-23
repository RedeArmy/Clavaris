package com.clavaris.common.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** JPA row mapping for {@code audit_events} (TD-SEC-007) — plain data holder by design. */
@SuppressWarnings({"PMD.ShortVariable", "PMD.DataClass"})
@Entity
@Table(name = "audit_events")
public class AuditEventEntity {

  @Id private UUID id;

  @Column(name = "actor_type", nullable = false)
  private String actorType;

  @Column(name = "actor_id", nullable = false)
  private String actorId;

  @Column(nullable = false)
  private String action;

  @Column(name = "target_type", nullable = false)
  private String targetType;

  @Column(name = "target_id")
  private String targetId;

  @Column private String detail;

  @Column(name = "occurred_at", nullable = false)
  private Instant occurredAt;

  protected AuditEventEntity() {}

  public AuditEventEntity(
      final UUID id,
      final String actorType,
      final String actorId,
      final String action,
      final String targetType,
      final String targetId,
      final String detail,
      final Instant occurredAt) {
    this.id = id;
    this.actorType = actorType;
    this.actorId = actorId;
    this.action = action;
    this.targetType = targetType;
    this.targetId = targetId;
    this.detail = detail;
    this.occurredAt = occurredAt;
  }

  public UUID getId() {
    return id;
  }

  public String getActorType() {
    return actorType;
  }

  public String getActorId() {
    return actorId;
  }

  public String getAction() {
    return action;
  }

  public String getTargetType() {
    return targetType;
  }

  public String getTargetId() {
    return targetId;
  }

  public String getDetail() {
    return detail;
  }

  public Instant getOccurredAt() {
    return occurredAt;
  }
}
