package com.clavaris.clientregistry.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * JPA row mapping for {@code oauth_clients} (data-model.md §2). {@code redirectUris}/{@code
 * allowedGrantTypes}/{@code allowedScopes} are {@code jsonb} arrays (TD-ARCH-003; they were {@code
 * text} before V20261026130000) — same as {@link PlatformClientEntity#getAllowedScopes()}. The Java
 * field stays the JSON text; serialization happens in {@link JpaOAuthClientRepository}, not here.
 * LongVariable: {@code postLogoutRedirectUris} is the exact OIDC spec term, not arbitrarily long —
 * same precedent as {@code OAuthClient}'s own suppression.
 */
@Entity
@Table(name = "oauth_clients")
public class OAuthClientEntity {

  @Id private UUID id;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(name = "client_id", nullable = false)
  private String clientId;

  @Column(name = "client_secret_hash", nullable = false)
  private String clientSecretHash;

  @Column(name = "redirect_uris", nullable = false)
  @JdbcTypeCode(SqlTypes.JSON)
  private String redirectUris;

  @Column(name = "allowed_grant_types", nullable = false)
  @JdbcTypeCode(SqlTypes.JSON)
  private String allowedGrantTypes;

  @Column(name = "allowed_scopes", nullable = false)
  @JdbcTypeCode(SqlTypes.JSON)
  private String allowedScopes;

  // TD-SEC-026/ADR-0017: per-client consent requirement — see OAuthClient's own Javadoc.
  @Column(name = "require_consent", nullable = false)
  private boolean requireConsent;

  // TD-FUT-018: same JSON-array convention as redirectUris/allowedGrantTypes/allowedScopes
  // above — see OAuthClient's own Javadoc.
  @Column(name = "post_logout_redirect_uris", nullable = false)
  @JdbcTypeCode(SqlTypes.JSON)
  private String postLogoutRedirectUris;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  // SDE-III review, 2026-09-11: same deactivate-without-delete convention as
  // OrganizationClientEntity/PlatformClientEntity's own identical column.
  @Column(nullable = false)
  private boolean active;

  // SDE-III review, 2026-09-15: closes a real lost-update race — see
  // ConcurrentClientModificationException's own Javadoc for the full rationale. @Version, not a
  // plain @Column: Hibernate manages this field itself (SELECTs it, includes it in every UPDATE's
  // WHERE clause, increments it, and throws on a mismatch) — no application code ever sets it.
  @Version
  @Column(nullable = false)
  private int version;

  protected OAuthClientEntity() {}

  // One parameter per persisted column — same convention as every other *Entity in this codebase
  // (PasswordCredentialEntity, SigningKeyEntity, ...), just the first with more than 7 columns.
  @SuppressWarnings({"java:S107", "PMD.ExcessiveParameterList"})
  public OAuthClientEntity(
      final UUID id,
      final UUID organizationId,
      final String clientId,
      final String clientSecretHash,
      final String redirectUris,
      final String allowedGrantTypes,
      final String allowedScopes,
      final boolean requireConsent,
      final String postLogoutRedirectUris,
      final Instant createdAt,
      final boolean active,
      final int version) {
    this.id = id;
    this.organizationId = organizationId;
    this.clientId = clientId;
    this.clientSecretHash = clientSecretHash;
    this.redirectUris = redirectUris;
    this.allowedGrantTypes = allowedGrantTypes;
    this.allowedScopes = allowedScopes;
    this.requireConsent = requireConsent;
    this.postLogoutRedirectUris = postLogoutRedirectUris;
    this.createdAt = createdAt;
    this.active = active;
    this.version = version;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public String getClientId() {
    return clientId;
  }

  public String getClientSecretHash() {
    return clientSecretHash;
  }

  public String getRedirectUris() {
    return redirectUris;
  }

  public String getAllowedGrantTypes() {
    return allowedGrantTypes;
  }

  public String getAllowedScopes() {
    return allowedScopes;
  }

  public boolean isRequireConsent() {
    return requireConsent;
  }

  public String getPostLogoutRedirectUris() {
    return postLogoutRedirectUris;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public boolean isActive() {
    return active;
  }

  public int getVersion() {
    return version;
  }
}
