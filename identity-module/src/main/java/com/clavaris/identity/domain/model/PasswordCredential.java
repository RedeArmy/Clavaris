package com.clavaris.identity.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * BR-ID-01: a password is never stored or handled in plaintext past the point it's hashed — {@code
 * passwordHash} is the only form this class ever holds. Deliberately a separate class from {@link
 * Account}, not a nullable field on it (data-model.md §2): keeps BR-ID-02 (never zero auth methods)
 * a natural fact about which credential rows exist for an account, not a null-check special case.
 *
 * <p>Shared state (every field except {@link #accountId()}) lives on {@link
 * AbstractPasswordCredential} — see its own Javadoc for why this pair shares a base (TD-ARCH-009).
 *
 * <p>PMD.ShortVariable: {@code id} names exactly what it is — same convention {@link
 * AbstractPasswordCredential}'s own identical suppression already documents for this same
 * constructor parameter.
 */
@SuppressWarnings("PMD.ShortVariable")
public final class PasswordCredential extends AbstractPasswordCredential<AccountId> {

  private PasswordCredential(
      final UUID id,
      final AccountId accountId,
      final String passwordHash,
      final Instant updatedAt) {
    super(id, accountId, passwordHash, updatedAt);
  }

  /**
   * @param passwordHash the already-hashed value (ADR-0005: Argon2id) — this factory never sees or
   *     accepts a raw password; hashing happens at the port boundary (application layer's {@code
   *     PasswordHasher}), not here, so the domain never needs to know which algorithm.
   */
  public static PasswordCredential issue(final AccountId accountId, final String passwordHash) {
    return new PasswordCredential(UUID.randomUUID(), accountId, passwordHash, Instant.now());
  }

  /**
   * Rehydrates an existing row — preserves the real persisted {@code id}/{@code updatedAt}, same
   * discipline as {@code SigningKey#reconstitute}/{@code OAuthClient#reconstitute}. Used by {@code
   * JpaAccountRepository} to load an {@code Account} back out for {@code
   * AuthenticateWithPasswordService} to verify against.
   */
  public static PasswordCredential reconstitute(
      final UUID id,
      final AccountId accountId,
      final String passwordHash,
      final Instant updatedAt) {
    return new PasswordCredential(id, accountId, passwordHash, updatedAt);
  }

  public AccountId accountId() {
    return owningId();
  }
}
