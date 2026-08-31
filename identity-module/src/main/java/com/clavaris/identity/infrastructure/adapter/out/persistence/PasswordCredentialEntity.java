package com.clavaris.identity.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA row mapping for {@code password_credentials} (data-model.md §2). Shared columns live on
 * {@link AbstractPasswordCredentialEntity} — only {@code account_id} is declared here (TD-ARCH-009,
 * closed 2026-08-31).
 */
@SuppressWarnings("PMD.ShortVariable")
@Entity
@Table(name = "password_credentials")
public class PasswordCredentialEntity extends AbstractPasswordCredentialEntity {

  @Column(name = "account_id", nullable = false)
  private UUID accountId;

  protected PasswordCredentialEntity() {
    super();
  }

  public PasswordCredentialEntity(
      final UUID id, final UUID accountId, final String passwordHash, final Instant updatedAt) {
    super(id, passwordHash, updatedAt);
    this.accountId = accountId;
  }

  public UUID getAccountId() {
    return accountId;
  }
}
