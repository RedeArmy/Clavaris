package com.clavaris.identity.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * BR-ID-01: a password is never stored or handled in plaintext past the point it's hashed — {@code
 * passwordHash} is the only form this class ever holds. Deliberately a separate class from {@link
 * Account}, not a nullable field on it (data-model.md §2): keeps BR-ID-02 (never zero auth methods)
 * a natural fact about which credential rows exist for an account, not a null-check special case.
 *
 * <p>Same record-style-accessor rationale as {@link Account} for the PMD suppressions below.
 */
@SuppressWarnings({
  "PMD.DataClass",
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.ShortVariable",
  "PMD.ShortMethodName"
})
public final class PasswordCredential {

  private final UUID id;
  private final AccountId accountId;
  private final String passwordHash;
  private final Instant updatedAt;

  private PasswordCredential(
      final UUID id,
      final AccountId accountId,
      final String passwordHash,
      final Instant updatedAt) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.accountId = Objects.requireNonNull(accountId, "accountId must not be null");
    this.passwordHash = Objects.requireNonNull(passwordHash, "passwordHash must not be null");
    this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    if (passwordHash.isBlank()) {
      // Guards against a hasher implementation bug producing an empty hash reaching persistence
      // silently — an account that "authenticates" against an empty hash is a security bug, not
      // a validation nicety.
      throw new IllegalArgumentException("passwordHash must not be blank");
    }
  }

  /**
   * @param passwordHash the already-hashed value (ADR-0005: Argon2id) — this factory never sees or
   *     accepts a raw password; hashing happens at the port boundary (application layer's {@code
   *     PasswordHasher}), not here, so the domain never needs to know which algorithm.
   */
  public static PasswordCredential issue(final AccountId accountId, final String passwordHash) {
    return new PasswordCredential(UUID.randomUUID(), accountId, passwordHash, Instant.now());
  }

  public UUID id() {
    return id;
  }

  public AccountId accountId() {
    return accountId;
  }

  public String passwordHash() {
    return passwordHash;
  }

  public Instant updatedAt() {
    return updatedAt;
  }
}
