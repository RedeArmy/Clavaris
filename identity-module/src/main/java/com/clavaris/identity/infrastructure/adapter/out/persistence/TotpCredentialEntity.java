package com.clavaris.identity.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** ADR-0040: JPA mapping of {@code totp_credentials}. */
@Entity
@Table(name = "totp_credentials")
public class TotpCredentialEntity {

  @Id private UUID id;

  @Column(name = "account_id", nullable = false, unique = true)
  private UUID accountId;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(name = "secret_encrypted", nullable = false)
  private String secretEncrypted;

  @Column(name = "confirmed_at")
  private Instant confirmedAt;

  @Column(name = "last_used_step", nullable = false)
  private long lastUsedStep;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected TotpCredentialEntity() {
    // JPA only.
  }

  public TotpCredentialEntity(
      final UUID id,
      final UUID accountId,
      final UUID organizationId,
      final String secretEncrypted,
      final Instant confirmedAt,
      final long lastUsedStep,
      final Instant createdAt) {
    this.id = id;
    this.accountId = accountId;
    this.organizationId = organizationId;
    this.secretEncrypted = secretEncrypted;
    this.confirmedAt = confirmedAt;
    this.lastUsedStep = lastUsedStep;
    this.createdAt = createdAt;
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

  public String getSecretEncrypted() {
    return secretEncrypted;
  }

  public Instant getConfirmedAt() {
    return confirmedAt;
  }

  public long getLastUsedStep() {
    return lastUsedStep;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
