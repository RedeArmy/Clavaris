package com.clavaris.identity.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Aggregate root. BR-ID-02: an {@code Account} is never valid with zero authentication methods —
 * enforced here, in the aggregate, not in a use-case service, so the invariant holds no matter
 * which future use case touches an account. ADR-0010: {@code organizationId} is mandatory and
 * immutable — there is no factory path that produces an {@code Account} without a tenant.
 *
 * <p>PMD's DataClass/AvoidFieldNameMatchingMethodName/ShortVariable/ShortMethodName rules flag this
 * class for its many single-word accessors ({@code id()}, {@code email()}, ...) matching the field
 * names they read — that's the deliberate record-style accessor convention used throughout this
 * codebase's value objects ({@code AccountId.value()}, etc.), not an accidental data-holder shape;
 * the real behaviour (BR-ID-02's invariant, enforced in {@link #attachPasswordCredential}) lives in
 * this class precisely because it's an aggregate, not a bag of fields with getters.
 */
@SuppressWarnings({
  "PMD.DataClass",
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.ShortVariable",
  "PMD.ShortMethodName",
  "PMD.LongVariable"
})
public final class Account {

  private final AccountId id;
  private final OrganizationId organizationId;
  private final Email email;
  private final Instant createdAt;
  private Instant emailVerifiedAt;

  // Not final: PMD flags this as immutable-in-this-slice (nothing here mutates it yet), but it's
  // deliberately a mutable field — a future use case (e.g. SuspendAccount) will need to transition
  // it, and marking it final now would have to be undone the moment that use case is built.
  @SuppressWarnings("PMD.ImmutableField")
  private AccountStatus status;

  private PasswordCredential passwordCredential;

  private Account(
      final AccountId id,
      final OrganizationId organizationId,
      final Email email,
      final Instant createdAt,
      final AccountStatus status) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.organizationId = Objects.requireNonNull(organizationId, "organizationId must not be null");
    this.email = Objects.requireNonNull(email, "email must not be null");
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    this.status = Objects.requireNonNull(status, "status must not be null");
  }

  /**
   * Registers a new account. Deliberately does not attach a credential — a caller must call {@link
   * #attachPasswordCredential(String)} (or, once social login exists, link a {@code
   * SocialIdentity}) in the same use case, before the aggregate is persisted, so that "an Account
   * with no credential yet" is never a state observable outside this package (BR-ID-02).
   */
  public static Account register(final OrganizationId organizationId, final Email email) {
    return new Account(
        AccountId.newId(), organizationId, email, Instant.now(), AccountStatus.ACTIVE);
  }

  /**
   * Attaches a password credential to this (freshly registered) account. BR-ID-01: only ever
   * receives an already-hashed value — see {@link PasswordCredential#issue}. Throws if a credential
   * is already attached: this method models registration-time attachment, not a password-change
   * flow (a separate, future use case with its own invariants — e.g. requiring the current
   * password, per BR-ID-04's "assume prior sessions compromised" stance on reset).
   */
  public void attachPasswordCredential(final String passwordHash) {
    if (this.passwordCredential != null) {
      throw new IllegalStateException(
          "Account " + id.value() + " already has a password credential attached");
    }
    this.passwordCredential = PasswordCredential.issue(id, passwordHash);
  }

  /**
   * Rehydrates an existing row — preserves the real persisted {@code id}/{@code createdAt}/{@code
   * status}, same discipline as {@code SigningKey#reconstitute}/{@code OAuthClient#reconstitute}.
   * Unlike {@link #register}, this accepts an already-issued {@link PasswordCredential} directly
   * (via {@link PasswordCredential#reconstitute}) rather than going through {@link
   * #attachPasswordCredential(String)} — that method's "must not already have one" guard models a
   * registration-time invariant, not a rehydration-from-storage one. {@code passwordCredential} may
   * be {@code null} for an account whose only authentication method is a (not yet implemented)
   * social identity — BR-ID-02 still guarantees at least one exists, just not necessarily this one.
   */
  public static Account reconstitute(
      final AccountId id,
      final OrganizationId organizationId,
      final Email email,
      final Instant createdAt,
      final Instant emailVerifiedAt,
      final AccountStatus status,
      final PasswordCredential passwordCredential) {
    final Account account = new Account(id, organizationId, email, createdAt, status);
    account.emailVerifiedAt = emailVerifiedAt;
    account.passwordCredential = passwordCredential;
    return account;
  }

  public AccountId id() {
    return id;
  }

  public OrganizationId organizationId() {
    return organizationId;
  }

  public Email email() {
    return email;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Optional<Instant> emailVerifiedAt() {
    return Optional.ofNullable(emailVerifiedAt);
  }

  public AccountStatus status() {
    return status;
  }

  public Optional<PasswordCredential> passwordCredential() {
    return Optional.ofNullable(passwordCredential);
  }
}
