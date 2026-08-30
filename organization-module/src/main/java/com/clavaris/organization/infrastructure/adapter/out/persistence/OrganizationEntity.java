package com.clavaris.organization.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA row mapping for {@code organizations} (data-model.md §2, ADR-0010, ADR-0012, ADR-0020) —
 * plain persistence-mapping data holder by design, same rationale as identity-module's own {@code
 * AccountEntity}. {@code allowedSocialProviders} is stored as raw {@code text} (JSON array) —
 * {@link JpaOrganizationRepository} owns the (de)serialization, same convention {@code
 * OAuthClientEntity}'s own {@code allowedScopes} column already establishes; this class never
 * parses it itself.
 */
@SuppressWarnings({"PMD.ShortVariable", "PMD.DataClass", "PMD.LongVariable"})
@Entity
@Table(name = "organizations")
public class OrganizationEntity {

  @Id private UUID id;

  @Column(nullable = false)
  private String name;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "owner_platform_account_id", nullable = false)
  private UUID ownerPlatformAccountId;

  @Column(name = "social_login_enabled", nullable = false)
  private boolean socialLoginEnabled;

  @Column(name = "allowed_social_providers")
  private String allowedSocialProviders;

  protected OrganizationEntity() {}

  public OrganizationEntity(
      final UUID id,
      final String name,
      final Instant createdAt,
      final UUID ownerPlatformAccountId,
      final boolean socialLoginEnabled,
      final String allowedSocialProviders) {
    this.id = id;
    this.name = name;
    this.createdAt = createdAt;
    this.ownerPlatformAccountId = ownerPlatformAccountId;
    this.socialLoginEnabled = socialLoginEnabled;
    this.allowedSocialProviders = allowedSocialProviders;
  }

  public UUID getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public UUID getOwnerPlatformAccountId() {
    return ownerPlatformAccountId;
  }

  public boolean isSocialLoginEnabled() {
    return socialLoginEnabled;
  }

  public String getAllowedSocialProviders() {
    return allowedSocialProviders;
  }
}
