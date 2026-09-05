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
 * <p>PMD's AvoidFieldNameMatchingMethodName/ShortVariable/ShortMethodName rules flag this class for
 * its many single-word accessors ({@code id()}, {@code email()}, ...) matching the field names they
 * read — that's the deliberate record-style accessor convention used throughout this codebase's
 * value objects ({@code AccountId.value()}, etc.), not an accidental data-holder shape.
 * (PMD.DataClass itself no longer fires here — enough real behaviour now lives in this class, e.g.
 * {@link #verifyEmail()}/{@link #resetPasswordCredential}, that PMD stopped considering it a bag of
 * fields, which is exactly the point.) TooManyMethods is suppressed below: an aggregate root
 * accumulating one more mutator per use case that touches it (BR-ID-02, BR-ID-04, BR-ID-05) is
 * growth in the right place, not a sign this class should be split.
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

  // Not final: mutated by suspend()/reactivate() (BR-ID-08) — the future use case this field's own
  // comment used to anticipate has now arrived, so the PMD.ImmutableField suppression that used to
  // sit here is gone too (PMD's own UnnecessaryWarningSuppression rule correctly flags a
  // suppression for a violation that no longer fires).
  private AccountStatus status;

  private PasswordCredential passwordCredential;

  // ADR-0024 §4: an optional, additional identifier — Account.email stays the mandatory, primary
  // identity (see AccountAuthenticationPolicy's own Javadoc for why making email itself optional
  // is out of scope) — never set at construction, only via assignUsername below, same "attach
  // after the fact" convention attachPasswordCredential already establishes.
  private Username username;

  // Clerk "session tasks" parity: an admin-forced "must change password before this account may
  // finish signing in again" marker (null for the overwhelming common case — never forced). Not a
  // boolean: the timestamp itself is useful audit context (when was this required), same reasoning
  // emailVerifiedAt already establishes for "presence/absence plus a timestamp" over a bare flag.
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
  // PMD.NullAssignment: the null below deliberately CLEARS passwordResetRequiredAt (an
  // already-satisfied requirement), not an accidental discard of a value worth keeping — see the
  // comment on that line for why.
  @SuppressWarnings("PMD.NullAssignment")
  public void attachPasswordCredential(final String passwordHash) {
    if (this.passwordCredential != null) {
      throw new IllegalStateException(
          "Account " + id.value() + " already has a password credential attached");
    }
    this.passwordCredential = PasswordCredential.issue(id, passwordHash);
    // Clerk "session tasks" parity: same "any real password-setting call satisfies the
    // requirement" reasoning as resetPasswordCredential's own identical statement — covers the
    // edge case of a password-optional account (ADR-0024 §5) being forced to set its first
    // password rather than rotate an existing one.
    this.passwordResetRequiredAt = null;
  }

  /**
   * ADR-0024 §4: assigns this (freshly registered) account's username — same "attach after the
   * fact, never at construction" convention {@link #attachPasswordCredential} already establishes.
   * {@code RegisterAccountService} is the only caller in v1 (no separate "change username" use case
   * exists yet) — throws if one is already assigned, same registration-time-only invariant {@link
   * #attachPasswordCredential}'s own guard establishes for its own field.
   */
  public void assignUsername(final Username username) {
    if (this.username != null) {
      throw new IllegalStateException("Account " + id.value() + " already has a username assigned");
    }
    this.username = Objects.requireNonNull(username, "username must not be null");
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
   *
   * @param username ADR-0024 §4: {@code null} for every account that never set one (the common
   *     case) — see {@link #assignUsername} for why this is never set at construction time.
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
   * Confirms the email of record — {@code ConfirmEmailVerificationService} calls this only after
   * validating a single-use {@code VerificationToken} (BR-ID-05). Idempotent by design: a token is
   * single-use so this normally runs once, but re-confirming an already-verified account is a
   * harmless no-op, not an error worth failing the request over.
   */
  public void verifyEmail() {
    if (this.emailVerifiedAt == null) {
      this.emailVerifiedAt = Instant.now();
    }
  }

  /**
   * Replaces the account's password credential's hash in place — the password-reset flow
   * (BR-ID-04), not registration-time attachment ({@link #attachPasswordCredential}), which is why
   * this method exists separately and has no "must not already have one" guard. Requires an
   * existing credential: a reset presupposes something to reset, and an account with only a (not
   * yet implemented) social identity has no password to replace — that case is the caller's (the
   * use case's) responsibility to reject before ever reaching this method, not this method's to
   * silently attach a first one.
   *
   * <p>Deliberately reuses the existing credential's own {@code id} via {@link
   * PasswordCredential#reconstitute} rather than {@link PasswordCredential#issue}, which mints a
   * fresh random one — confirmed live (a real integration test, not just inspection) that minting a
   * new id here makes {@code JpaAccountRepository#save} attempt an INSERT of a second {@code
   * password_credentials} row for the same account, violating the table's own {@code
   * UNIQUE(account_id)} constraint (data-model.md §2) the moment a real reset ran end to end. This
   * is an update to the one credential row an account may ever have, not a replacement of it with
   * an unrelated new one.
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
    // Clerk "session tasks" parity: a real password change (via any of this method's callers,
    // self-service ConfirmPasswordResetService included) always satisfies an outstanding
    // requirement — there is no separate "acknowledge the requirement without actually changing
    // the password" path, so clearing it here (rather than in each individual caller) is the one
    // place this can never be missed.
    this.passwordResetRequiredAt = null;
  }

  /**
   * Clerk "session tasks" parity: an operator-forced "must set a new password before this account
   * may finish signing in again" — {@code ForcePasswordResetForAccountService}'s own state
   * transition. Idempotent, same reasoning as {@link #suspend()}: re-forcing an already-pending
   * requirement doesn't stamp a fresh timestamp — the original "since when" is the more useful
   * audit fact to keep.
   */
  public void requirePasswordReset() {
    if (this.passwordResetRequiredAt == null) {
      this.passwordResetRequiredAt = Instant.now();
    }
  }

  /**
   * Reversible ban/suspend — {@code SuspendAccountService}'s own state transition. {@code
   * AuthenticateWithPasswordService} already rejects any non-{@link AccountStatus#ACTIVE} account
   * before touching the password hash at all, so this method's only job is the transition itself;
   * killing an already-live session/token is the calling service's own responsibility (same "domain
   * mutates state, use case orchestrates side effects" split every other mutator here follows).
   * Idempotent, same reasoning as {@link #verifyEmail()}: re-suspending an already-{@code
   * SUSPENDED} account is a harmless no-op, not an error worth failing the request over. Does
   * nothing to a {@link AccountStatus#DELETED} account — that status is terminal (BR-DATA-03, a
   * hard delete in v1), never reversible by this method.
   */
  public void suspend() {
    if (this.status == AccountStatus.ACTIVE) {
      this.status = AccountStatus.SUSPENDED;
    }
  }

  /**
   * Reverses {@link #suspend()} — {@code ReactivateAccountService}'s own state transition.
   * Idempotent, same reasoning as {@link #suspend()}. Does nothing to a {@link
   * AccountStatus#DELETED} account, same terminal-status reasoning as {@link #suspend()}.
   */
  public void reactivate() {
    if (this.status == AccountStatus.SUSPENDED) {
      this.status = AccountStatus.ACTIVE;
    }
  }
}
