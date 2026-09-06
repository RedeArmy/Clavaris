package com.clavaris.clientregistry.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA row mapping for {@code client_domain_configs} — plain data holder by design, same rationale
 * as {@code RedirectPolicyEntity}. {@code mode}/{@code verificationStatus} are persisted as plain
 * strings (never {@code @Enumerated}) — same convention {@code OrganizationEntity}'s own {@code
 * environment} column already establishes; the domain layer's enum is the only place that name
 * matters. Every domain-specific column is nullable — {@code SHARED} mode (never a custom domain
 * requested) is the normal state.
 */
@SuppressWarnings({"PMD.ShortVariable", "PMD.DataClass", "PMD.LongVariable"})
@Entity
@Table(name = "client_domain_configs")
public class ClientDomainConfigEntity {

  @Id private UUID id;

  @Column(name = "oauth_client_id", nullable = false)
  private UUID oauthClientId;

  @Column(name = "mode")
  private String mode;

  @Column(name = "hostname")
  private String hostname;

  @Column(name = "verification_status")
  private String verificationStatus;

  @Column(name = "dns_txt_challenge_token")
  private String dnsTxtChallengeToken;

  @Column(name = "embedding_origin")
  private String embeddingOrigin;

  @Column(name = "verified_at")
  private Instant verifiedAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected ClientDomainConfigEntity() {}

  // One parameter per persisted column — same convention as every other *Entity in this codebase.
  // java:S107/PMD.ExcessiveParameterList: ten persisted columns is what this row genuinely looks
  // like, same OAuthClientEntity precedent for a wide, flat JPA row.
  @SuppressWarnings({"java:S107", "PMD.ExcessiveParameterList"})
  public ClientDomainConfigEntity(
      final UUID id,
      final UUID oauthClientId,
      final String mode,
      final String hostname,
      final String verificationStatus,
      final String dnsTxtChallengeToken,
      final String embeddingOrigin,
      final Instant verifiedAt,
      final Instant createdAt,
      final Instant updatedAt) {
    this.id = id;
    this.oauthClientId = oauthClientId;
    this.mode = mode;
    this.hostname = hostname;
    this.verificationStatus = verificationStatus;
    this.dnsTxtChallengeToken = dnsTxtChallengeToken;
    this.embeddingOrigin = embeddingOrigin;
    this.verifiedAt = verifiedAt;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOauthClientId() {
    return oauthClientId;
  }

  public String getMode() {
    return mode;
  }

  public String getHostname() {
    return hostname;
  }

  public String getVerificationStatus() {
    return verificationStatus;
  }

  public String getDnsTxtChallengeToken() {
    return dnsTxtChallengeToken;
  }

  public String getEmbeddingOrigin() {
    return embeddingOrigin;
  }

  public Instant getVerifiedAt() {
    return verifiedAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
