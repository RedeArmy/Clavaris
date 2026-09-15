package com.clavaris.identity.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Aggregate root. BR-ID-02: never valid with zero authentication methods, enforced here so no
 * future use case can bypass it. ADR-0010: {@code organizationId} is mandatory and immutable.
 *
 * <p>PMD suppressions below back the record-style accessor convention documented once in
 * coding-standards.md §3a, not an accidental data-holder shape. {@code TooManyMethods}: one more
 * mutator per use case that touches this aggregate (BR-ID-02, BR-ID-04, BR-ID-05) is growth in the
 * right place, not a signal to split the class.
 */
@SuppressWarnings({
  "PMD.TooManyMethods",
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

  // Not final: mutated by suspend()/reactivate() (BR-ID-08).
  private AccountStatus status;

  private PasswordCredential passwordCredential;

  // ADR-0024 §4: optional secondary identifier, attached after registration via assignUsername —
  // email stays the mandatory primary identity.
  private Username username;

  // Clerk "session tasks" parity: admin-forced password-reset marker, null when not required. A
  // timestamp, not a boolean, for its audit value (same reasoning as emailVerifiedAt).
  private Instant passwordResetRequiredAt;

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
   * Registers a new account without a credential attached — the caller must attach one (password or
   * a social identity, ADR-0020) before persisting, so "no credential yet" is never observable
   * outside this package (BR-ID-02).
   */
  public static Account register(final OrganizationId organizationId, final Email email) {
    return new Account(
        AccountId.newId(), organizationId, email, Instant.now(), AccountStatus.ACTIVE);
  }

  /**
   * Attaches a password credential at registration time — BR-ID-01: {@code passwordHash} must
   * already be hashed (see {@link PasswordCredential#issue}). Throws if one is already attached;
   * password changes go through {@link #resetPasswordCredential}, not this method.
   */
  // PMD.NullAssignment: deliberately clears passwordResetRequiredAt, not an accidental discard.
  @SuppressWarnings("PMD.NullAssignment")
  public void attachPasswordCredential(final String passwordHash) {
    if (this.passwordCredential != null) {
      throw new IllegalStateException(
          "Account " + id.value() + " already has a password credential attached");
    }
    this.passwordCredential = PasswordCredential.issue(id, passwordHash);
    // Clerk "session tasks" parity: setting a password always clears any pending requirement.
    this.passwordResetRequiredAt = null;
  }

  /**
   * ADR-0024 §4: assigns the username (registration-time only, no separate "change" use case in v1)
   * — throws if one is already assigned, same invariant {@link #attachPasswordCredential} enforces
   * for its own field.
   */
  public void assignUsername(final Username username) {
    if (this.username != null) {
      throw new IllegalStateException("Account " + id.value() + " already has a username assigned");
    }
    this.username = Objects.requireNonNull(username, "username must not be null");
  }

  /**
   * Rehydrates an existing row, preserving the real {@code id}/{@code createdAt}/{@code status}.
   * {@code passwordCredential}/{@code username} may be {@code null} (a social-identity-only
   * account, ADR-0020; a username never assigned, ADR-0024 §4) — BR-ID-02 still guarantees at least
   * one authentication method exists.
   */
  @SuppressWarnings("java:S107")
  public static Account reconstitute(
      final AccountId id,
      final OrganizationId organizationId,
      final Email email,
      final Instant createdAt,
      final Instant emailVerifiedAt,
      final AccountStatus status,
      final PasswordCredential passwordCredential,
      final Username username,
      final Instant passwordResetRequiredAt) {
    final Account account = new Account(id, organizationId, email, createdAt, status);
    account.emailVerifiedAt = emailVerifiedAt;
    account.passwordCredential = passwordCredential;
    account.username = username;
    account.passwordResetRequiredAt = passwordResetRequiredAt;
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

  public Optional<Username> username() {
    return Optional.ofNullable(username);
  }

  public Optional<Instant> passwordResetRequiredAt() {
    return Optional.ofNullable(passwordResetRequiredAt);
  }

  /**
   * Confirms the email (BR-ID-05, after a single-use {@code VerificationToken} check). Idempotent —
   * re-confirming an already-verified account is a harmless no-op.
   */
  public void verifyEmail() {
    if (this.emailVerifiedAt == null) {
      this.emailVerifiedAt = Instant.now();
    }
  }

  /**
   * Password-reset flow (BR-ID-04) — replaces the hash in place; requires an existing credential.
   * {@link #attachPasswordCredential} is registration-time only, not this. Reuses the existing
   * row's own {@code id} rather than minting a fresh one (confirmed live: a fresh id
   * duplicate-inserts against {@code password_credentials}' own {@code UNIQUE(account_id)}).
   */
  // PMD.NullAssignment: same deliberate-clear rationale as attachPasswordCredential's own
  // identical suppression.
  @SuppressWarnings("PMD.NullAssignment")
  public void resetPasswordCredential(final String newPasswordHash) {
    if (this.passwordCredential == null) {
      throw new IllegalStateException(
          "Account " + id.value() + " has no password credential to reset");
    }
    this.passwordCredential =
        PasswordCredential.reconstitute(
            this.passwordCredential.id(), id, newPasswordHash, Instant.now());
    // Clerk "session tasks" parity: any real password change clears an outstanding requirement.
    this.passwordResetRequiredAt = null;
  }

  /**
   * Clerk "session tasks" parity: operator-forced password reset before the next sign-in completes.
   * Idempotent — re-forcing keeps the original timestamp, the more useful audit fact.
   */
  public void requirePasswordReset() {
    if (this.passwordResetRequiredAt == null) {
      this.passwordResetRequiredAt = Instant.now();
    }
  }

  /**
   * Reversible suspend (BR-ID-08) — {@code AuthenticateWithPasswordService} already rejects any
   * non-{@code ACTIVE} account, so killing a live session/token is the calling use case's job, not
   * this method's. Idempotent; no-ops on a terminal {@code DELETED} account (BR-DATA-03).
   */
  public void suspend() {
    if (this.status == AccountStatus.ACTIVE) {
      this.status = AccountStatus.SUSPENDED;
    }
  }

  /** Reverses {@link #suspend()} — same idempotent and terminal-status handling. */
  public void reactivate() {
    if (this.status == AccountStatus.SUSPENDED) {
      this.status = AccountStatus.ACTIVE;
    }
  }
}
