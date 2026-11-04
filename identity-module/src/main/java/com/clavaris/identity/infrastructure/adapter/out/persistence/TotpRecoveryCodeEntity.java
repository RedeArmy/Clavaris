package com.clavaris.identity.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** ADR-0040: JPA mapping of {@code totp_recovery_codes}. */
@Entity
@Table(name = "totp_recovery_codes")
public class TotpRecoveryCodeEntity {

  @Id private UUID id;

  @Column(name = "account_id", nullable = false)
  private UUID accountId;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(name = "code_hash", nullable = false)
  private String codeHash;

  @Column(name = "used_at")
  private Instant usedAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected TotpRecoveryCodeEntity() {
    // JPA only.
  }

  public TotpRecoveryCodeEntity(
      final UUID id,
      final UUID accountId,
      final UUID organizationId,
      final String codeHash,
      final Instant createdAt) {
    this.id = id;
    this.accountId = accountId;
    this.organizationId = organizationId;
    this.codeHash = codeHash;
    this.createdAt = createdAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getAccountId() {
    return accountId;
  }

  public String getCodeHash() {
    return codeHash;
  }

  public Instant getUsedAt() {
    return usedAt;
  }
}
