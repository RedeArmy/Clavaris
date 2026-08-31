package com.clavaris.identity.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA row mapping for {@code platform_password_credentials} — mirrors {@link
 * PasswordCredentialEntity}. Shared columns live on {@link AbstractPasswordCredentialEntity}
 * (TD-ARCH-009, closed 2026-08-31) — only {@code platform_account_id} is declared here.
 */
@SuppressWarnings("PMD.ShortVariable")
@Entity
@Table(name = "platform_password_credentials")
public class PlatformPasswordCredentialEntity extends AbstractPasswordCredentialEntity {

  @Column(name = "platform_account_id", nullable = false)
  private UUID platformAccountId;

  protected PlatformPasswordCredentialEntity() {
    super();
  }

  public PlatformPasswordCredentialEntity(
      final UUID id,
      final UUID platformAccountId,
      final String passwordHash,
      final Instant updatedAt) {
    super(id, passwordHash, updatedAt);
    this.platformAccountId = platformAccountId;
  }

  public UUID getPlatformAccountId() {
    return platformAccountId;
  }
}
