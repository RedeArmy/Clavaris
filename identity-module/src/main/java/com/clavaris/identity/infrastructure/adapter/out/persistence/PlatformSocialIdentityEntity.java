package com.clavaris.identity.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA row mapping for {@code platform_social_identities} (migration {@code V20260828090002},
 * ADR-0020) — mirrors {@link SocialIdentityEntity}, scoped to {@code platform_account_id} instead
 * of {@code account_id}.
 */
@SuppressWarnings({"PMD.DataClass", "PMD.ShortVariable"})
@Entity
@Table(name = "platform_social_identities")
public class PlatformSocialIdentityEntity {

  @Id private UUID id;

  @Column(name = "platform_account_id", nullable = false)
  private UUID platformAccountId;

  @Column(nullable = false, length = 32)
  private String provider;

  @Column(name = "provider_user_id", nullable = false)
  private String providerUserId;

  @Column(name = "linked_at", nullable = false)
  private Instant linkedAt;

  protected PlatformSocialIdentityEntity() {}

  public PlatformSocialIdentityEntity(
      final UUID id,
      final UUID platformAccountId,
      final String provider,
      final String providerUserId,
      final Instant linkedAt) {
    this.id = id;
    this.platformAccountId = platformAccountId;
    this.provider = provider;
    this.providerUserId = providerUserId;
    this.linkedAt = linkedAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getPlatformAccountId() {
    return platformAccountId;
  }

  public String getProvider() {
    return provider;
  }

  public String getProviderUserId() {
    return providerUserId;
  }

  public Instant getLinkedAt() {
    return linkedAt;
  }
}
