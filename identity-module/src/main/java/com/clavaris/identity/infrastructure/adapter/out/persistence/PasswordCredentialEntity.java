package com.clavaris.identity.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA row mapping for {@code password_credentials} (data-model.md §2). See {@link AccountEntity}
 * for why PMD.DataClass/ShortVariable are suppressed here too.
 */
@SuppressWarnings({"PMD.DataClass", "PMD.ShortVariable"})
@Entity
@Table(name = "password_credentials")
public class PasswordCredentialEntity {

  @Id private UUID id;

  @Column(name = "account_id", nullable = false)
  private UUID accountId;

  @Column(name = "password_hash", nullable = false)
  private String passwordHash;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected PasswordCredentialEntity() {}

  public PasswordCredentialEntity(
      final UUID id, final UUID accountId, final String passwordHash, final Instant updatedAt) {
    this.id = id;
    this.accountId = accountId;
    this.passwordHash = passwordHash;
    this.updatedAt = updatedAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getAccountId() {
    return accountId;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
