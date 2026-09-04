package com.clavaris.clientregistry.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import java.util.UUID;

/**
 * Shared column mapping for {@code platform_clients} and {@code organization_clients} — {@code
 * PlatformClientEntity} and {@code OrganizationClientEntity} both extend this instead of
 * duplicating the same six columns (SonarCloud-flagged duplication) — same root cause {@code
 * AbstractEventOutboxEntity}'s own Javadoc (common module) already documents for the cross-module
 * equivalent of this problem; this one stays local to client-registry-module since both concrete
 * entities already live in the same module and package.
 *
 * <p>Deliberately {@code @MappedSuperclass}, not a shared {@code @Entity}/{@code @Table}: each
 * credential type still owns its own separate, independently-migrated table (own name, own
 * migration file, {@code OrganizationClientEntity}'s own additional {@code organization_id} column)
 * — this only removes the duplicated field/column boilerplate, not the two credentials' deliberate
 * structural separation (ADR-0023: distinct trust boundaries, not one table with an optional
 * column).
 */
@SuppressWarnings({"PMD.DataClass", "PMD.ShortVariable", "PMD.AbstractClassWithoutAbstractMethod"})
@MappedSuperclass
public abstract class AbstractClientCredentialEntity {

  @Id protected UUID id;

  @Column(name = "client_id", nullable = false)
  protected String clientId;

  @Column(name = "client_secret_hash", nullable = false)
  protected String clientSecretHash;

  @Column(name = "allowed_scopes", nullable = false, columnDefinition = "text")
  protected String allowedScopes;

  @Column(name = "created_at", nullable = false)
  protected Instant createdAt;

  @Column(nullable = false)
  protected boolean active;

  protected AbstractClientCredentialEntity() {}

  protected AbstractClientCredentialEntity(
      final UUID id,
      final String clientId,
      final String clientSecretHash,
      final String allowedScopes,
      final Instant createdAt,
      final boolean active) {
    this.id = id;
    this.clientId = clientId;
    this.clientSecretHash = clientSecretHash;
    this.allowedScopes = allowedScopes;
    this.createdAt = createdAt;
    this.active = active;
  }

  public UUID getId() {
    return id;
  }

  public String getClientId() {
    return clientId;
  }

  public String getClientSecretHash() {
    return clientSecretHash;
  }

  public String getAllowedScopes() {
    return allowedScopes;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public boolean isActive() {
    return active;
  }
}
