package com.clavaris.identity.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA row mapping for {@code platform_verification_tokens} — mirrors {@link
 * VerificationTokenEntity}, same "type as a plain String" convention. Shared columns live on {@link
 * AbstractVerificationTokenEntity} (TD-ARCH-009, closed 2026-08-31) — only {@code
 * platform_account_id} is declared here.
 */
@SuppressWarnings("PMD.ShortVariable")
@Entity
@Table(name = "platform_verification_tokens")
public class PlatformVerificationTokenEntity extends AbstractVerificationTokenEntity {

  @Column(name = "platform_account_id", nullable = false)
  private UUID platformAccountId;

  protected PlatformVerificationTokenEntity() {
    super();
  }

  @SuppressWarnings("java:S107")
  public PlatformVerificationTokenEntity(
      final UUID id,
      final UUID platformAccountId,
      final String type,
      final String tokenHash,
      final Instant expiresAt,
      final Instant consumedAt) {
    super(id, type, tokenHash, expiresAt, consumedAt);
    this.platformAccountId = platformAccountId;
  }

  public UUID getPlatformAccountId() {
    return platformAccountId;
  }
}
