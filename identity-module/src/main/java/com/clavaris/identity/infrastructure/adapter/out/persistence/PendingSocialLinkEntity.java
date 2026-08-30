package com.clavaris.identity.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA row mapping for {@code pending_social_links} (migration {@code V20260828090001}, ADR-0020
 * Decision 1, BR-ID-09). Same "plain String columns, domain type conversion owned by the repository
 * adapter" convention as {@link SocialIdentityEntity}.
 *
 * <p>PMD.LongVariable: {@code confirmationTokenHash} names exactly what it is, same convention
 * {@code PendingSocialLink}'s own class-level suppression already documents for this exact name.
 */
@SuppressWarnings({"PMD.DataClass", "PMD.ShortVariable", "PMD.LongVariable"})
@Entity
@Table(name = "pending_social_links")
public class PendingSocialLinkEntity {

  @Id private UUID id;

  @Column(name = "account_id", nullable = false)
  private UUID accountId;

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

  protected PendingSocialLinkEntity() {}

  @SuppressWarnings("java:S107") // one parameter per persisted column — same convention as
  // VerificationTokenEntity's own identical suppression.
  public PendingSocialLinkEntity(
      final UUID id,
      final UUID accountId,
      final String provider,
      final String providerUserId,
      final String confirmationTokenHash,
      final Instant expiresAt,
      final Instant consumedAt) {
    this.id = id;
    this.accountId = accountId;
    this.provider = provider;
    this.providerUserId = providerUserId;
    this.confirmationTokenHash = confirmationTokenHash;
    this.expiresAt = expiresAt;
    this.consumedAt = consumedAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getAccountId() {
    return accountId;
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
