package com.clavaris.identity.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import java.util.UUID;

/**
 * Shared column mapping for {@link PasswordCredentialEntity}/{@link
 * PlatformPasswordCredentialEntity} — same {@code @MappedSuperclass} extraction {@link
 * AbstractPendingSocialLinkEntity}/{@link AbstractVerificationTokenEntity} already established,
 * applied here to TD-ARCH-009's own second remaining pair (named 2026-08-31).
 *
 * <p>Every column here except the owning-id one (added by each subclass — {@code account_id} vs.
 * {@code platform_account_id}, different tables entirely) is identical between both tables — pure
 * persistence boilerplate with zero domain meaning of its own.
 */
@MappedSuperclass
@SuppressWarnings({"PMD.ShortVariable", "PMD.AbstractClassWithoutAbstractMethod"})
public abstract class AbstractPasswordCredentialEntity {

  @Id protected UUID id;

  @Column(name = "password_hash", nullable = false)
  protected String passwordHash;

  @Column(name = "updated_at", nullable = false)
  protected Instant updatedAt;

  protected AbstractPasswordCredentialEntity() {}

  protected AbstractPasswordCredentialEntity(
      final UUID id, final String passwordHash, final Instant updatedAt) {
    this.id = id;
    this.passwordHash = passwordHash;
    this.updatedAt = updatedAt;
  }

  public UUID getId() {
    return id;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
