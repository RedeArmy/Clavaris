package com.clavaris.organization.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** JPA row mapping for {@code session_policies} — plain data holder by design. */
@SuppressWarnings({"PMD.ShortVariable", "PMD.DataClass", "PMD.LongVariable"})
@Entity
@Table(name = "session_policies")
public class SessionPolicyEntity {

  @Id private UUID id;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(name = "maximum_lifetime_minutes", nullable = false)
  private int maximumLifetimeMinutes;

  @Column(name = "inactivity_timeout_minutes", nullable = false)
  private int inactivityTimeoutMinutes;

  @Column(name = "reverification_window_minutes", nullable = false)
  private int reverificationWindowMinutes;

  @Column(name = "multi_session_handling_enabled", nullable = false)
  private boolean multiSessionHandlingEnabled;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected SessionPolicyEntity() {}

  @SuppressWarnings("java:S107")
  public SessionPolicyEntity(
      final UUID id,
      final UUID organizationId,
      final int maximumLifetimeMinutes,
      final int inactivityTimeoutMinutes,
      final int reverificationWindowMinutes,
      final boolean multiSessionHandlingEnabled,
      final Instant createdAt,
      final Instant updatedAt) {
    this.id = id;
    this.organizationId = organizationId;
    this.maximumLifetimeMinutes = maximumLifetimeMinutes;
    this.inactivityTimeoutMinutes = inactivityTimeoutMinutes;
    this.reverificationWindowMinutes = reverificationWindowMinutes;
    this.multiSessionHandlingEnabled = multiSessionHandlingEnabled;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public int getMaximumLifetimeMinutes() {
    return maximumLifetimeMinutes;
  }

  public int getInactivityTimeoutMinutes() {
    return inactivityTimeoutMinutes;
  }

  public int getReverificationWindowMinutes() {
    return reverificationWindowMinutes;
  }

  public boolean isMultiSessionHandlingEnabled() {
    return multiSessionHandlingEnabled;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
