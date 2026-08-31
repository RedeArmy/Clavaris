package com.clavaris.identity.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * BR-ID-01's hash-not-plaintext principle, applied to {@link PlatformAccount} — structurally
 * separate from {@link PasswordCredential} (never a shared type keyed by a generic id) for the same
 * reason {@link PlatformAccountId} is its own type: platform-tier and tenant-tier identities are
 * deliberately never mixed, even where the shape is identical.
 *
 * <p>Shared state lives on {@link AbstractPasswordCredential} — see its own Javadoc for why this
 * pair shares a base (TD-ARCH-009).
 *
 * <p>PMD.ShortVariable: {@code id} names exactly what it is — same convention {@link
 * AbstractPasswordCredential}'s own identical suppression already documents for this same
 * constructor parameter.
 */
@SuppressWarnings("PMD.ShortVariable")
public final class PlatformPasswordCredential
    extends AbstractPasswordCredential<PlatformAccountId> {

  private PlatformPasswordCredential(
      final UUID id,
      final PlatformAccountId platformAccountId,
      final String passwordHash,
      final Instant updatedAt) {
    super(id, platformAccountId, passwordHash, updatedAt);
  }

  public static PlatformPasswordCredential issue(
      final PlatformAccountId platformAccountId, final String passwordHash) {
    return new PlatformPasswordCredential(
        UUID.randomUUID(), platformAccountId, passwordHash, Instant.now());
  }

  public static PlatformPasswordCredential reconstitute(
      final UUID id,
      final PlatformAccountId platformAccountId,
      final String passwordHash,
      final Instant updatedAt) {
    return new PlatformPasswordCredential(id, platformAccountId, passwordHash, updatedAt);
  }

  public PlatformAccountId platformAccountId() {
    return owningId();
  }
}
