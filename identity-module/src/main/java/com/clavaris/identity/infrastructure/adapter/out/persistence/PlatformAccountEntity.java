package com.clavaris.identity.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA row mapping for {@code platform_accounts} (data-model.md §2, ADR-0012) — mirrors {@link
 * AccountEntity}, no {@code organization_id} column.
 */
@SuppressWarnings({"PMD.DataClass", "PMD.ShortVariable"})
@Entity
@Table(name = "platform_accounts")
public class PlatformAccountEntity {

  @Id private UUID id;

  @Column(nullable = false)
  private String email;

  @Column(name = "email_verified_at")
  private Instant emailVerifiedAt;

  @Column(nullable = false)
  private String status;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected PlatformAccountEntity() {}

  public PlatformAccountEntity(
      final UUID id,
      final String email,
      final Instant emailVerifiedAt,
      final String status,
      final Instant createdAt) {
    this.id = id;
    this.email = email;
    this.emailVerifiedAt = emailVerifiedAt;
    this.status = status;
    this.createdAt = createdAt;
  }

  public UUID getId() {
    return id;
  }

  public String getEmail() {
    return email;
  }

  public Instant getEmailVerifiedAt() {
    return emailVerifiedAt;
  }

  public String getStatus() {
    return status;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
