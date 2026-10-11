package com.clavaris.organization.infrastructure.adapter.out.persistence;

import com.clavaris.organization.domain.model.SocialProvider;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA row mapping for {@code organization_social_connection_settings} — a plain data holder by
 * design, same convention as {@code OrganizationSocialCredentialEntity}.
 */
@SuppressWarnings({"PMD.ShortVariable", "PMD.DataClass", "PMD.LongVariable"})
@Entity
@Table(name = "organization_social_connection_settings")
public class SocialConnectionSettingsEntity {

  @Id private UUID id;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Enumerated(EnumType.STRING)
  @Column(name = "provider", nullable = false, length = 20)
  private SocialProvider provider;

  @Column(name = "block_email_subaddresses", nullable = false)
  private boolean blockEmailSubaddresses;

  @Column(name = "always_show_account_selector", nullable = false)
  private boolean alwaysShowAccountSelector;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected SocialConnectionSettingsEntity() {}

  @SuppressWarnings("java:S107")
  public SocialConnectionSettingsEntity(
      final UUID id,
      final UUID organizationId,
      final SocialProvider provider,
      final boolean blockEmailSubaddresses,
      final boolean alwaysShowAccountSelector,
      final Instant createdAt,
      final Instant updatedAt) {
    this.id = id;
    this.organizationId = organizationId;
    this.provider = provider;
    this.blockEmailSubaddresses = blockEmailSubaddresses;
    this.alwaysShowAccountSelector = alwaysShowAccountSelector;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public SocialProvider getProvider() {
    return provider;
  }

  public boolean isBlockEmailSubaddresses() {
    return blockEmailSubaddresses;
  }

  public boolean isAlwaysShowAccountSelector() {
    return alwaysShowAccountSelector;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
