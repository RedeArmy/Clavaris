package com.clavaris.identity.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA row mapping for {@code pending_social_links} (migration {@code V20260828090001}, ADR-0020
 * Decision 1, BR-ID-09). Shared columns live on {@link AbstractPendingSocialLinkEntity} — only
 * {@code account_id} is declared here, same split {@code EventOutboxEntity} already establishes
 * against its own {@code AbstractEventOutboxEntity}.
 */
@SuppressWarnings({"PMD.ShortVariable", "PMD.LongVariable"})
@Entity
@Table(name = "pending_social_links")
public class PendingSocialLinkEntity extends AbstractPendingSocialLinkEntity {

  @Column(name = "account_id", nullable = false)
  private UUID accountId;

  protected PendingSocialLinkEntity() {
    super();
  }

  @SuppressWarnings("java:S107") // one parameter per persisted column — same convention as
  // AbstractPendingSocialLinkEntity's own identical suppression.
  public PendingSocialLinkEntity(
      final UUID id,
      final UUID accountId,
      final String provider,
      final String providerUserId,
      final String confirmationTokenHash,
      final Instant expiresAt,
      final Instant consumedAt) {
    super(id, provider, providerUserId, confirmationTokenHash, expiresAt, consumedAt);
    this.accountId = accountId;
  }

  public UUID getAccountId() {
    return accountId;
  }
}
