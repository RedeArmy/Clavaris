package com.clavaris.identity.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA row mapping for {@code verification_tokens} (data-model.md §2, BR-ID-04/BR-ID-05). {@code
 * type} is a plain {@code String}, not the domain's {@code VerificationTokenType} enum — same
 * convention as {@code AccountEntity.status}: this entity never references a {@code domain.model}
 * type at all, the mapping adapter ({@code JpaVerificationTokenRepository}) owns the conversion.
 *
 * <p>Shared columns live on {@link AbstractVerificationTokenEntity} — only {@code account_id} is
 * declared here (TD-ARCH-009, closed 2026-08-31), same split {@link PendingSocialLinkEntity}
 * already establishes against its own {@link AbstractPendingSocialLinkEntity}.
 */
@SuppressWarnings("PMD.ShortVariable")
@Entity
@Table(name = "verification_tokens")
public class VerificationTokenEntity extends AbstractVerificationTokenEntity {

  @Column(name = "account_id", nullable = false)
  private UUID accountId;

  protected VerificationTokenEntity() {
    super();
  }

  // One parameter per persisted column — same convention as every other *Entity in this codebase
  // (RefreshTokenEntity, SigningKeyEntity, OAuthClientEntity, ...) that crosses 7 columns.
  @SuppressWarnings("java:S107")
  public VerificationTokenEntity(
      final UUID id,
      final UUID accountId,
      final String type,
      final String tokenHash,
      final Instant expiresAt,
      final Instant consumedAt) {
    super(id, type, tokenHash, expiresAt, consumedAt);
    this.accountId = accountId;
  }

  public UUID getAccountId() {
    return accountId;
  }
}
