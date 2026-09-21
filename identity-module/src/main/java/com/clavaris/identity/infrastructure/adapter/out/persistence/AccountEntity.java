package com.clavaris.identity.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA row mapping for {@code accounts} (data-model.md §2) — persistence-only shape, deliberately
 * separate from {@code domain.model.Account}: the domain aggregate never carries
 * {@code @Entity}/{@code @Column} annotations (the hexagonal dependency rule, verified by {@code
 * HexagonalArchitectureTest}). Mapping to/from the domain type happens in {@link
 * JpaAccountRepository}, not here.
 *
 * <p>PMD.DataClass/ShortVariable are expected here, not a smell to fix: a JPA entity is *supposed*
 * to be a plain persistence-mapping data holder by hexagonal design — the real behaviour lives in
 * {@code domain.model.Account}, deliberately not here.
 */
@SuppressWarnings({"PMD.DataClass", "PMD.ShortVariable", "PMD.LongVariable"})
@Entity
@Table(name = "accounts")
public class AccountEntity {

  @Id private UUID id;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(nullable = false)
  private String email;

  @Column(name = "email_verified_at")
  private Instant emailVerifiedAt;

  @Column(nullable = false)
  private String status;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  // ADR-0024 §4: nullable — most accounts never set one. Own column, not a nullable-but-part-of-
  // some-other-shape field, same "just add the scalar column" precedent email_verified_at already
  // establishes for this same table.
  @Column private String username;

  // Clerk "session tasks" parity: nullable — the overwhelming common case is never forced. Same
  // "just add the scalar column" precedent as username/email_verified_at above.
  @Column(name = "password_reset_required_at")
  private Instant passwordResetRequiredAt;

  // Clerk dashboard "Users" tab parity (SDE-III review, 2026-09-19) — all four nullable, same
  // "just add the scalar column" precedent as every field above.
  @Column(name = "first_name")
  private String firstName;

  @Column(name = "last_name")
  private String lastName;

  @Column(name = "phone_number")
  private String phoneNumber;

  @Column(name = "last_signed_in_at")
  private Instant lastSignedInAt;

  // ADR-0026: the Clavaris-owned avatar endpoint's own storage key/external reference — nullable,
  // same "just add the scalar column" precedent as every field above.
  @Column(name = "picture_url")
  private String pictureUrl;

  // ADR-0026, Clerk "User permissions" parity — both NOT NULL with a database-side default
  // (unlike every nullable column above), same rationale the migration's own comment documents.
  @Column(name = "can_delete_own_account", nullable = false)
  private boolean canDeleteOwnAccount;

  @Column(name = "bypasses_device_trust", nullable = false)
  private boolean bypassesDeviceTrust;

  /** Required by JPA/Hibernate — never called directly by adapter code. */
  protected AccountEntity() {}

  @SuppressWarnings({"java:S107", "PMD.ExcessiveParameterList"})
  public AccountEntity(
      final UUID id,
      final UUID organizationId,
      final String email,
      final Instant emailVerifiedAt,
      final String status,
      final Instant createdAt,
      final String username,
      final Instant passwordResetRequiredAt,
      final String firstName,
      final String lastName,
      final String phoneNumber,
      final Instant lastSignedInAt,
      final String pictureUrl,
      final boolean canDeleteOwnAccount,
      final boolean bypassesDeviceTrust) {
    this.id = id;
    this.organizationId = organizationId;
    this.email = email;
    this.emailVerifiedAt = emailVerifiedAt;
    this.status = status;
    this.createdAt = createdAt;
    this.username = username;
    this.passwordResetRequiredAt = passwordResetRequiredAt;
    this.firstName = firstName;
    this.lastName = lastName;
    this.phoneNumber = phoneNumber;
    this.lastSignedInAt = lastSignedInAt;
    this.pictureUrl = pictureUrl;
    this.canDeleteOwnAccount = canDeleteOwnAccount;
    this.bypassesDeviceTrust = bypassesDeviceTrust;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrganizationId() {
    return organizationId;
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

  public String getUsername() {
    return username;
  }

  public Instant getPasswordResetRequiredAt() {
    return passwordResetRequiredAt;
  }

  public String getFirstName() {
    return firstName;
  }

  public String getLastName() {
    return lastName;
  }

  public String getPhoneNumber() {
    return phoneNumber;
  }

  public Instant getLastSignedInAt() {
    return lastSignedInAt;
  }

  public String getPictureUrl() {
    return pictureUrl;
  }

  public boolean isCanDeleteOwnAccount() {
    return canDeleteOwnAccount;
  }

  public boolean isBypassesDeviceTrust() {
    return bypassesDeviceTrust;
  }
}
