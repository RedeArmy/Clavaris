package com.clavaris.clientregistry.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA row mapping for {@code oauth_clients} (data-model.md §2). {@code redirectUris}/{@code
 * allowedGrantTypes}/{@code allowedScopes} are stored as {@code text} (JSON array) — same
 * convention as {@link PlatformClientEntity#getAllowedScopes()}; serialization happens in {@link
 * JpaOAuthClientRepository}, not here. LongVariable: {@code postLogoutRedirectUris} is the exact
 * OIDC spec term, not arbitrarily long — same precedent as {@code OAuthClient}'s own suppression.
 */
@SuppressWarnings({"PMD.DataClass", "PMD.ShortVariable", "PMD.LongVariable"})
@Entity
@Table(name = "oauth_clients")
public class OAuthClientEntity {

  // Code review finding (2026-09-01): PMD.AvoidDuplicateLiterals on "text" appearing 4 times —
  // one constant, not a per-@Column repeated literal.
  private static final String TEXT_COLUMN_TYPE = "text";

  @Id private UUID id;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(name = "client_id", nullable = false)
  private String clientId;

  @Column(name = "client_secret_hash", nullable = false)
  private String clientSecretHash;

  @Column(name = "redirect_uris", nullable = false, columnDefinition = TEXT_COLUMN_TYPE)
  private String redirectUris;

  @Column(name = "allowed_grant_types", nullable = false, columnDefinition = TEXT_COLUMN_TYPE)
  private String allowedGrantTypes;

  @Column(name = "allowed_scopes", nullable = false, columnDefinition = TEXT_COLUMN_TYPE)
  private String allowedScopes;

  // TD-SEC-026/ADR-0017: per-client consent requirement — see OAuthClient's own Javadoc.
  @Column(name = "require_consent", nullable = false)
  private boolean requireConsent;

  // TD-FUT-018: same text/JSON-array convention as redirectUris/allowedGrantTypes/allowedScopes
  // above — see OAuthClient's own Javadoc.
  @Column(name = "post_logout_redirect_uris", nullable = false, columnDefinition = TEXT_COLUMN_TYPE)
  private String postLogoutRedirectUris;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

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
      final Instant createdAt) {
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
}
