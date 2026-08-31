package com.clavaris.identity.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import java.util.UUID;

/**
 * Shared column mapping for {@link PendingSocialLinkEntity}/{@link PendingPlatformSocialLinkEntity}
 * — same {@code @MappedSuperclass} extraction {@code AbstractEventOutboxEntity} (common module,
 * TD-ARCH-007) already established for a SonarCloud- flagged duplication, applied here to a second
 * instance (TD-ARCH-009, widened 2026-08-30): 34.8%/32 lines duplicated between the two subclasses
 * before this extraction. Lives in identity-module, not {@code common}: both subclasses already
 * belong to this one module (ADR-0020's own tenant/platform social-login pair), unlike the
 * event-outbox case that genuinely spans two modules — {@code coding-standards.md} §5's own
 * module-scoping rule for when {@code common} is warranted doesn't apply here.
 *
 * <p>Every column here except the owning-id one (added by each subclass — {@code account_id} vs.
 * {@code platform_account_id}, different tables entirely) is identical between both tables — pure
 * persistence boilerplate with zero domain meaning of its own ({@code coding-standards.md} §5's own
 * "would only ever change in lockstep" test): BR-ID-09's confirmation-link shape (provider,
 * providerUserId, a hashed confirmation token, an expiry, an optional consumption timestamp) is the
 * same requirement for both tiers, not a coincidence either copy could plausibly diverge from
 * later.
 *
 * <p>No abstract method of its own — same PMD.AbstractClassWithoutAbstractMethod rationale as
 * {@code AbstractEventOutboxEntity}'s own identical suppression: the point of abstractness here is
 * "never map this on its own" ({@code @MappedSuperclass} has no {@code @Table}), not "force
 * subclasses to implement something."
 */
@MappedSuperclass
@SuppressWarnings({
  "PMD.DataClass",
  "PMD.ShortVariable",
  "PMD.LongVariable",
  "PMD.AbstractClassWithoutAbstractMethod"
})
public abstract class AbstractPendingSocialLinkEntity {

  @Id protected UUID id;

  @Column(nullable = false, length = 32)
  protected String provider;

  @Column(name = "provider_user_id", nullable = false)
  protected String providerUserId;

  @Column(name = "confirmation_token_hash", nullable = false)
  protected String confirmationTokenHash;

  @Column(name = "expires_at", nullable = false)
  protected Instant expiresAt;

  @Column(name = "consumed_at")
  protected Instant consumedAt;

  protected AbstractPendingSocialLinkEntity() {}

  // One parameter per shared column — same convention AbstractEventOutboxEntity's own identical
  // suppression documents for this exact shape of constructor.
  @SuppressWarnings("java:S107")
  protected AbstractPendingSocialLinkEntity(
      final UUID id,
      final String provider,
      final String providerUserId,
      final String confirmationTokenHash,
      final Instant expiresAt,
      final Instant consumedAt) {
    this.id = id;
    this.provider = provider;
    this.providerUserId = providerUserId;
    this.confirmationTokenHash = confirmationTokenHash;
    this.expiresAt = expiresAt;
    this.consumedAt = consumedAt;
  }

  public UUID getId() {
    return id;
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
