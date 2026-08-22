package com.clavaris.identity.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA row mapping for {@code platform_password_credentials} — mirrors {@link
 * PasswordCredentialEntity}.
 */
@SuppressWarnings({"PMD.DataClass", "PMD.ShortVariable"})
@Entity
@Table(name = "platform_password_credentials")
public class PlatformPasswordCredentialEntity {

  @Id private UUID id;

  @Column(name = "platform_account_id", nullable = false)
  private UUID platformAccountId;

  @Column(name = "password_hash", nullable = false)
  private String passwordHash;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected PlatformPasswordCredentialEntity() {}

  public PlatformPasswordCredentialEntity(
      final UUID id,
      final UUID platformAccountId,
      final String passwordHash,
      final Instant updatedAt) {
    this.id = id;
    this.platformAccountId = platformAccountId;
    this.passwordHash = passwordHash;
    this.updatedAt = updatedAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getPlatformAccountId() {
    return platformAccountId;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
