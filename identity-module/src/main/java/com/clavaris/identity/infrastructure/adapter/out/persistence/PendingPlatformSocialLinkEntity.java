package com.clavaris.identity.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA row mapping for {@code pending_platform_social_links} (migration {@code V20260828090003},
 * ADR-0020 Decision 1, BR-ID-09) — mirrors {@link PendingSocialLinkEntity}, scoped to {@code
 * platform_account_id} instead of {@code account_id}.
 *
 * <p>PMD.LongVariable: {@code confirmationTokenHash} names exactly what it is, same convention
 * {@code PendingSocialLinkEntity}'s own class-level suppression already documents.
 */
@SuppressWarnings({"PMD.DataClass", "PMD.ShortVariable", "PMD.LongVariable"})
@Entity
@Table(name = "pending_platform_social_links")
public class PendingPlatformSocialLinkEntity {

  @Id private UUID id;

  @Column(name = "platform_account_id", nullable = false)
  private UUID platformAccountId;

  @Column(nullable = false, length = 32)
  private String provider;

  @Column(name = "provider_user_id", nullable = false)
  private String providerUserId;

  @Column(name = "confirmation_token_hash", nullable = false)
  private String confirmationTokenHash;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "consumed_at")
  private Instant consumedAt;

  protected PendingPlatformSocialLinkEntity() {}

  @SuppressWarnings("java:S107") // one parameter per persisted column — same convention as
  // PendingSocialLinkEntity's own identical suppression.
  public PendingPlatformSocialLinkEntity(
      final UUID id,
      final UUID platformAccountId,
      final String provider,
      final String providerUserId,
      final String confirmationTokenHash,
      final Instant expiresAt,
      final Instant consumedAt) {
    this.id = id;
    this.platformAccountId = platformAccountId;
    this.provider = provider;
    this.providerUserId = providerUserId;
    this.confirmationTokenHash = confirmationTokenHash;
    this.expiresAt = expiresAt;
    this.consumedAt = consumedAt;
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

  public String getConfirmationTokenHash() {
    return confirmationTokenHash;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public Instant getConsumedAt() {
    return consumedAt;
  }
}
