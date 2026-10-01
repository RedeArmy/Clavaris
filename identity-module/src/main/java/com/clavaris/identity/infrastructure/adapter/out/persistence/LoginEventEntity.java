package com.clavaris.identity.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA row mapping for {@code login_events} (TD-FUT-034) — persistence-only shape, deliberately
 * separate from {@code domain.model.LoginEvent}, same hexagonal-dependency rule every other {@code
 * *Entity} in this package already follows.
 */
@SuppressWarnings({"PMD.ShortVariable", "PMD.DataClass"})
@Entity
@Table(name = "login_events")
public class LoginEventEntity {

  @Id private UUID id;

  @Column(name = "account_id", nullable = false)
  private UUID accountId;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(name = "occurred_at", nullable = false)
  private Instant occurredAt;

  /** Required by JPA/Hibernate — never called directly by adapter code. */
  protected LoginEventEntity() {}

  public LoginEventEntity(
      final UUID id, final UUID accountId, final UUID organizationId, final Instant occurredAt) {
    this.id = id;
    this.accountId = accountId;
    this.organizationId = organizationId;
    this.occurredAt = occurredAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getAccountId() {
    return accountId;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public Instant getOccurredAt() {
    return occurredAt;
  }
}
