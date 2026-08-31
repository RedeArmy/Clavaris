package com.clavaris.identity.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import java.util.UUID;

/**
 * Shared column mapping for {@link VerificationTokenEntity}/{@link PlatformVerificationTokenEntity}
 * — same {@code @MappedSuperclass} extraction {@link AbstractPendingSocialLinkEntity} already
 * established for the structurally identical {@code PendingSocialLink}/{@code
 * PendingPlatformSocialLink} pair, applied here to close the remaining half of TD-ARCH-009 (named
 * 2026-08-30, closed 2026-08-31).
 *
 * <p>Every column here except the owning-id one (added by each subclass — {@code account_id} vs.
 * {@code platform_account_id}, different tables entirely) is identical between both tables — pure
 * persistence boilerplate with zero domain meaning of its own ({@code coding-standards.md} §5's own
 * "would only ever change in lockstep" test).
 *
 * <p>No abstract method of its own — same PMD.AbstractClassWithoutAbstractMethod rationale as
 * {@link AbstractPendingSocialLinkEntity}'s own identical suppression.
 */
@MappedSuperclass
@SuppressWarnings({"PMD.DataClass", "PMD.ShortVariable", "PMD.AbstractClassWithoutAbstractMethod"})
public abstract class AbstractVerificationTokenEntity {

  @Id protected UUID id;

  @Column(nullable = false, length = 32)
  protected String type;

  @Column(name = "token_hash", nullable = false)
  protected String tokenHash;

  @Column(name = "expires_at", nullable = false)
  protected Instant expiresAt;

  @Column(name = "consumed_at")
  protected Instant consumedAt;

  protected AbstractVerificationTokenEntity() {}

  // One parameter per shared column — same convention AbstractPendingSocialLinkEntity's own
  // identical suppression documents for this exact shape of constructor.
  @SuppressWarnings("java:S107")
  protected AbstractVerificationTokenEntity(
      final UUID id,
      final String type,
      final String tokenHash,
      final Instant expiresAt,
      final Instant consumedAt) {
    this.id = id;
    this.type = type;
    this.tokenHash = tokenHash;
    this.expiresAt = expiresAt;
    this.consumedAt = consumedAt;
  }

  public UUID getId() {
    return id;
  }

  public String getType() {
    return type;
  }

  public String getTokenHash() {
    return tokenHash;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public Instant getConsumedAt() {
    return consumedAt;
  }
}
