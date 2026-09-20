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

  // ADR-0026: same "just add the scalar column" precedent AccountEntity's own identical fields
  // already establish.
  @Column(name = "first_name")
  private String firstName;

  @Column(name = "last_name")
  private String lastName;

  @Column(name = "picture_url")
  private String pictureUrl;

  protected PlatformAccountEntity() {}

  @SuppressWarnings("java:S107")
  public PlatformAccountEntity(
      final UUID id,
      final String email,
      final Instant emailVerifiedAt,
      final String status,
      final Instant createdAt,
      final String firstName,
      final String lastName,
      final String pictureUrl) {
    this.id = id;
    this.email = email;
    this.emailVerifiedAt = emailVerifiedAt;
    this.status = status;
    this.createdAt = createdAt;
    this.firstName = firstName;
    this.lastName = lastName;
    this.pictureUrl = pictureUrl;
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

  public String getFirstName() {
    return firstName;
  }

  public String getLastName() {
    return lastName;
  }

  public String getPictureUrl() {
    return pictureUrl;
  }
}
