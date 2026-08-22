package com.clavaris.organization.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA row mapping for {@code rate_limit_policies} (ADR-0010 §6.2) — plain data holder by design.
 */
@SuppressWarnings({"PMD.ShortVariable", "PMD.DataClass"})
@Entity
@Table(name = "rate_limit_policies")
public class RateLimitPolicyEntity {

  @Id private UUID id;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(name = "requests_per_minute", nullable = false)
  private int requestsPerMinute;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected RateLimitPolicyEntity() {}

  public RateLimitPolicyEntity(
      final UUID id,
      final UUID organizationId,
      final int requestsPerMinute,
      final Instant createdAt,
      final Instant updatedAt) {
    this.id = id;
    this.organizationId = organizationId;
    this.requestsPerMinute = requestsPerMinute;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public int getRequestsPerMinute() {
    return requestsPerMinute;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
