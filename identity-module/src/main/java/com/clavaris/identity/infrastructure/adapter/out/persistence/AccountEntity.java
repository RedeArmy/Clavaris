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
 * {@code @Entity}/{@code @Column} annotations (CLAUDE.md §7.2, verified by {@code
 * HexagonalArchitectureTest}). Mapping to/from the domain type happens in {@link
 * JpaAccountRepository}, not here.
 *
 * <p>PMD.DataClass/ShortVariable are expected here, not a smell to fix: a JPA entity is *supposed*
 * to be a plain persistence-mapping data holder by hexagonal design — the real behaviour lives in
 * {@code domain.model.Account}, deliberately not here.
 */
@SuppressWarnings({"PMD.DataClass", "PMD.ShortVariable"})
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

  /** Required by JPA/Hibernate — never called directly by adapter code. */
  protected AccountEntity() {}

  public AccountEntity(
      final UUID id,
      final UUID organizationId,
      final String email,
      final Instant emailVerifiedAt,
      final String status,
      final Instant createdAt) {
    this.id = id;
    this.organizationId = organizationId;
    this.email = email;
    this.emailVerifiedAt = emailVerifiedAt;
    this.status = status;
    this.createdAt = createdAt;
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
}
