package com.clavaris.identity.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Shared state for {@link PasswordCredential}/{@link PlatformPasswordCredential} — TD-ARCH-009's
 * own second remaining pair (named 2026-08-31, alongside {@code AbstractVerificationToken}): both
 * classes are identical except the owning-id type, same BR-ID-01 hash-not-plaintext invariant at
 * either tier, not a coincidence either copy could plausibly diverge from later.
 *
 * <p>Same "generic, not a mirror" reasoning as {@link AbstractPendingSocialLink}/{@link
 * AbstractVerificationToken}'s own Javadoc — no side effects, no tier-specific behavior; {@code I}
 * is the only thing that differs.
 *
 * <p>Package-private: only this package's own two subclasses ever need to see it. Same
 * record-style-accessor and structural-metric PMD suppressions as its siblings.
 */
@SuppressWarnings({
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.ShortVariable",
  "PMD.ShortMethodName",
  "PMD.AbstractClassWithoutAbstractMethod",
  "PMD.PublicMemberInNonPublicType",
  "PMD.DataClass"
})
abstract class AbstractPasswordCredential<I> {

  private final UUID id;
  private final I owningId;
  private final String passwordHash;
  private final Instant updatedAt;

  protected AbstractPasswordCredential(
      final UUID id, final I owningId, final String passwordHash, final Instant updatedAt) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.owningId = Objects.requireNonNull(owningId, "owningId must not be null");
    this.passwordHash = Objects.requireNonNull(passwordHash, "passwordHash must not be null");
    this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    if (passwordHash.isBlank()) {
      // Guards against a hasher implementation bug producing an empty hash reaching persistence
      // silently — an account that "authenticates" against an empty hash is a security bug, not
      // a validation nicety. Same check, same rationale, at either tier.
      throw new IllegalArgumentException("passwordHash must not be blank");
    }
  }

  public final UUID id() {
    return id;
  }

  protected final I owningId() {
    return owningId;
  }

  public final String passwordHash() {
    return passwordHash;
  }

  public final Instant updatedAt() {
    return updatedAt;
  }
}
