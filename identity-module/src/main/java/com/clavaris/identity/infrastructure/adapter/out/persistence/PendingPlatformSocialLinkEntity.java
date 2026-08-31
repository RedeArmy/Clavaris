package com.clavaris.identity.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA row mapping for {@code pending_platform_social_links} (migration {@code V20260828090003},
 * ADR-0020 Decision 1, BR-ID-09) — mirrors {@link PendingSocialLinkEntity}, scoped to {@code
 * platform_account_id} instead of {@code account_id}. Shared columns live on {@link
 * AbstractPendingSocialLinkEntity} — only {@code platform_account_id} is declared here, same split
 * {@code EventOutboxEntity} already establishes against its own {@code AbstractEventOutboxEntity}.
 */
@SuppressWarnings({"PMD.ShortVariable", "PMD.LongVariable"})
@Entity
@Table(name = "pending_platform_social_links")
public class PendingPlatformSocialLinkEntity extends AbstractPendingSocialLinkEntity {

  @Column(name = "platform_account_id", nullable = false)
  private UUID platformAccountId;

  protected PendingPlatformSocialLinkEntity() {
    super();
  }

  @SuppressWarnings("java:S107") // one parameter per persisted column — same convention as
  // AbstractPendingSocialLinkEntity's own identical suppression.
  public PendingPlatformSocialLinkEntity(
      final UUID id,
      final UUID platformAccountId,
      final String provider,
      final String providerUserId,
      final String confirmationTokenHash,
      final Instant expiresAt,
      final Instant consumedAt) {
    super(id, provider, providerUserId, confirmationTokenHash, expiresAt, consumedAt);
    this.platformAccountId = platformAccountId;
  }

  public UUID getPlatformAccountId() {
    return platformAccountId;
  }
}
