package com.clavaris.identity.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Aggregate root. BR-ID-02: never valid with zero authentication methods, enforced here so no
 * future use case can bypass it. ADR-0010: {@code organizationId} is mandatory and immutable.
 *
 * <p>The optional attributes live in four small immutable values ({@link AccountProfile}, {@link
 * AccountPermissions}, {@link RegistrationDecision}, {@link AccountMetadata}) instead of two dozen
 * loose fields, and persistence rehydrates through one {@link AccountSnapshot} instead of a
 * telescoping set of {@code reconstitute} overloads. The accessors below stay flat so no caller
 * needs to know how the state is grouped.
 *
 * <p>PMD suppressions back the record-style accessor convention documented once in
 * coding-standards.md §3a, not an accidental data-holder shape.
 */
// PMD.TooManyMethods/ExcessivePublicCount: one accessor per attribute plus one mutator per use case
// that touches this aggregate (BR-ID-02, BR-ID-04, BR-ID-05) is growth in the right place, not a
// signal to split it; the state itself is already grouped into the four values above.
@SuppressWarnings({
  "PMD.TooManyMethods",
  "PMD.ExcessivePublicCount",
})
public final class Account {

  private final AccountId id;
  private final OrganizationId organizationId;
  private Email email;
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

  // Moved forward by recordSignIn() on every successful authentication — the "Last signed in"
  // column. A plain field mutated post-construction: every caller that authenticates an Account
  // already holds the aggregate and saves it back, the natural place for this side effect.
  private Instant lastSignedInAt;

  private AccountProfile profile = AccountProfile.EMPTY;
  private AccountPermissions permissions = AccountPermissions.DEFAULT;
  private RegistrationDecision registration = RegistrationDecision.NONE;
  private AccountMetadata metadata = AccountMetadata.EMPTY;

  // The optimistic-lock value this aggregate was loaded (or last saved) at; see
  // ConcurrentAccountModificationException. Not domain state: it only travels with the aggregate.
  private int version;

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
   * Admin-initiated creation (dashboard "Users" tab) — same aggregate and invariants as {@link
   * #register(OrganizationId, Email)}, with the optional profile fields the create-user form takes.
   * Each of them may be {@code null}.
   */
  public static Account register(
      final OrganizationId organizationId,
      final Email email,
      final String firstName,
      final String lastName,
      final String phoneNumber) {
    final Account account = register(organizationId, email);
    account.profile = new AccountProfile(firstName, lastName, phoneNumber, null);
    return account;
  }

  /**
   * TD-FUT-019: for an Organization whose {@code selfRegistrationRequiresApproval()} policy is on —
   * the account exists and can be administered but cannot sign in (every {@code AuthenticateWith*}
   * service rejects a non-{@code ACTIVE} account) until {@link #approveRegistration()}. The caller
   * still attaches a credential before persisting (BR-ID-02): status governs usability, not that
   * invariant.
   */
  public static Account registerPendingApproval(
      final OrganizationId organizationId, final Email email) {
    return new Account(
        AccountId.newId(), organizationId, email, Instant.now(), AccountStatus.PENDING_APPROVAL);
  }

  /**
   * Rehydrates an existing row from its full persisted state, preserving the real {@code id}/{@code
   * createdAt}/{@code status}.
   */
  public static Account reconstitute(final AccountSnapshot snapshot) {
    final Account account =
        new Account(
            snapshot.id(),
            snapshot.organizationId(),
            snapshot.email(),
            snapshot.createdAt(),
            snapshot.status());
    account.emailVerifiedAt = snapshot.emailVerifiedAt();
    account.passwordCredential = snapshot.passwordCredential();
    account.username = snapshot.username();
    account.passwordResetRequiredAt = snapshot.passwordResetRequiredAt();
    account.lastSignedInAt = snapshot.lastSignedInAt();
    account.profile = Objects.requireNonNull(snapshot.profile(), "profile must not be null");
    account.permissions =
        Objects.requireNonNull(snapshot.permissions(), "permissions must not be null");
    account.registration =
        Objects.requireNonNull(snapshot.registration(), "registration must not be null");
    account.metadata = Objects.requireNonNull(snapshot.metadata(), "metadata must not be null");
    account.version = snapshot.version();
    return account;
  }

  /**
   * Convenience for the common case of an account with none of the optional attributes set — {@code
   * passwordCredential}/{@code username} may be {@code null} as in {@link AccountSnapshot}.
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
    return reconstitute(
        new AccountSnapshot(
            id,
            organizationId,
            email,
            createdAt,
            emailVerifiedAt,
            status,
            passwordCredential,
            username,
            passwordResetRequiredAt,
            null,
            AccountProfile.EMPTY,
            AccountPermissions.DEFAULT,
            RegistrationDecision.NONE,
            AccountMetadata.EMPTY,
            0));
  }

  /** Everything the persistence adapter writes, in one value. */
  public AccountSnapshot snapshot() {
    return new AccountSnapshot(
        id,
        organizationId,
        email,
        createdAt,
        emailVerifiedAt,
        status,
        passwordCredential,
        username,
        passwordResetRequiredAt,
        lastSignedInAt,
        profile,
        permissions,
        registration,
        metadata,
        version);
  }

  /**
   * Called by the persistence adapter after a successful save, so a second save of this same
   * aggregate in the same flow carries the version the first one produced instead of failing
   * against its own earlier write.
   */
  public void rememberPersistedVersion(final int persistedVersion) {
    this.version = persistedVersion;
  }

  /**
   * Attaches a password credential at registration time — BR-ID-01: {@code passwordHash} must
   * already be hashed (see {@link PasswordCredential#issue}). Throws if one is already attached;
   * password changes go through {@link #resetPasswordCredential}, not this method.
   */
  public void attachPasswordCredential(final String passwordHash) {
    attach(PasswordCredential.issue(id, passwordHash));
  }

  /**
   * As {@link #attachPasswordCredential}, for a random credential the person never chose and does
   * not know (a social sign-up, a registration without a password). It keeps "forgot password"
   * working but is not proof of who is asking (TD-FUT-053).
   */
  public void attachGeneratedPasswordCredential(final String passwordHash) {
    attach(PasswordCredential.issueGenerated(id, passwordHash));
  }

  // PMD.NullAssignment: deliberately clears passwordResetRequiredAt, not an accidental discard.
  @SuppressWarnings("PMD.NullAssignment")
  private void attach(final PasswordCredential credential) {
    if (this.passwordCredential != null) {
      throw new IllegalStateException(
          "Account " + id.value() + " already has a password credential attached");
    }
    this.passwordCredential = credential;
    // Clerk "session tasks" parity: setting a password always clears any pending requirement.
    this.passwordResetRequiredAt = null;
  }

  /** True if the person chose their password, so it can be asked for as proof (TD-FUT-053). */
  public boolean hasUserChosenPassword() {
    return passwordCredential != null && !passwordCredential.generated();
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

  public Optional<String> firstName() {
    return Optional.ofNullable(profile.firstName());
  }

  public Optional<String> lastName() {
    return Optional.ofNullable(profile.lastName());
  }

  public Optional<String> phoneNumber() {
    return Optional.ofNullable(profile.phoneNumber());
  }

  public Optional<Instant> lastSignedInAt() {
    return Optional.ofNullable(lastSignedInAt);
  }

  public Optional<String> pictureUrl() {
    return Optional.ofNullable(profile.pictureUrl());
  }

  public boolean canDeleteOwnAccount() {
    return permissions.canDeleteOwnAccount();
  }

  public boolean bypassesDeviceTrust() {
    return permissions.bypassesDeviceTrust();
  }

  public Optional<Instant> registrationDecidedAt() {
    return Optional.ofNullable(registration.decidedAt());
  }

  public Optional<String> registrationRejectionReason() {
    return Optional.ofNullable(registration.rejectionReason());
  }

  public Optional<String> publicMetadata() {
    return Optional.ofNullable(metadata.publicMetadata());
  }

  public Optional<String> privateMetadata() {
    return Optional.ofNullable(metadata.privateMetadata());
  }

  public Optional<String> unsafeMetadata() {
    return Optional.ofNullable(metadata.unsafeMetadata());
  }

  /**
   * Called by every successful authentication path (password, social) right before the account is
   * saved back. Unconditional, not idempotent-guarded like {@link #verifyEmail()}: it is meant to
   * move forward on every sign-in, not just the first.
   */
  public void recordSignIn() {
    this.lastSignedInAt = Instant.now();
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
   * TD-FUT-043: the self-service email change's final step — only ever called after a single-use
   * link delivered to the <em>new</em> address was consumed, so possession of it is itself the
   * verification and {@code email_verified_at} is stamped now. The caller owns the per-Organization
   * uniqueness check (the unique constraint remains the backstop).
   */
  public void changeEmail(final Email newEmail) {
    this.email = Objects.requireNonNull(newEmail, "newEmail must not be null");
    this.emailVerifiedAt = Instant.now();
  }

  /**
   * Password-reset flow (BR-ID-04) — replaces the hash in place; requires an existing credential.
   * {@link #attachPasswordCredential} is registration-time only, not this. Reuses the existing
   * row's own {@code id} rather than minting a fresh one (a fresh id duplicate-inserts against
   * {@code password_credentials}' own {@code UNIQUE(account_id)}).
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

  /**
   * A deliberately separate action from {@link #suspend()}, not an alias — see {@link
   * AccountStatus}'s own Javadoc. Same idempotent/terminal-status handling; only transitions out of
   * {@code ACTIVE}, never out of {@code SUSPENDED} (an account already suspended must be explicitly
   * reactivated first — the two states don't silently convert into one another).
   */
  public void ban() {
    if (this.status == AccountStatus.ACTIVE) {
      this.status = AccountStatus.BANNED;
    }
  }

  /** Reverses {@link #ban()} — same idempotent and terminal-status handling. */
  public void unban() {
    if (this.status == AccountStatus.BANNED) {
      this.status = AccountStatus.ACTIVE;
    }
  }

  /**
   * TD-FUT-019: only ever transitions a {@code PENDING_APPROVAL} account, a no-op from any other
   * status — the same defensive shape as {@link #suspend()}/{@link #ban()}. Whether it was
   * genuinely pending is the calling use case's job to check and report as a typed error; this is
   * the safety net, not the primary signal.
   */
  public void approveRegistration() {
    if (this.status == AccountStatus.PENDING_APPROVAL) {
      this.status = AccountStatus.ACTIVE;
      this.registration = RegistrationDecision.approvedAt(Instant.now());
    }
  }

  /**
   * TD-FUT-019: the rejection counterpart to {@link #approveRegistration()}, same idempotent,
   * {@code PENDING_APPROVAL}-only shape. {@code reason} may be {@code null}.
   */
  public void rejectRegistration(final String reason) {
    if (this.status == AccountStatus.PENDING_APPROVAL) {
      this.status = AccountStatus.REJECTED;
      this.registration = RegistrationDecision.rejectedAt(Instant.now(), reason);
    }
  }

  /**
   * ADR-0026: self-service picture upload (also an operator-initiated change) — always overwrites,
   * unlike {@link #applySocialProviderProfile}, since a deliberate replace is what this is for.
   */
  public void updateProfilePicture(final String pictureUrl) {
    this.profile =
        profile.withPictureUrl(Objects.requireNonNull(pictureUrl, "pictureUrl must not be null"));
  }

  /** Reverts to the generated-initials default avatar (ADR-0026) — idempotent. */
  public void removeProfilePicture() {
    this.profile = profile.withoutPicture();
  }

  /**
   * ADR-0026 / BR-ID-09 sibling decision: captures name and picture from a social provider's own
   * profile claims, called once right after {@link #register(OrganizationId, Email)} in a brand-new
   * social sign-up. Non-destructive (only fills what is still {@code null}), the correct contract
   * to publish whatever the one real call site happens to do today.
   */
  public void applySocialProviderProfile(
      final String firstName, final String lastName, final String pictureUrl) {
    this.profile = profile.filledFrom(firstName, lastName, pictureUrl);
  }

  /**
   * ADR-0026: self-service or operator-initiated name edit — always overwrites. A blank submitted
   * value is the caller's job to normalise to {@code null}, the convention every controller here
   * follows for optional text.
   */
  public void updateProfile(final String firstName, final String lastName) {
    this.profile = profile.withName(firstName, lastName);
  }

  /**
   * Operator-driven edit (dashboard "View Profile" > Profile tab). Unlike {@link #assignUsername},
   * a phone number has no uniqueness constraint, so this always overwrites; calling it only once is
   * a use-case-level policy ({@code UpdateAccountProfileService}), not a domain invariant.
   */
  public void updatePhoneNumber(final String phoneNumber) {
    this.profile = profile.withPhoneNumber(phoneNumber);
  }

  /**
   * Operator-controlled (dashboard "Settings" tab): gates {@code DeleteOwnAccountUseCase}'s
   * self-service "Delete account" action. Off by default — an Organization opts a specific Account
   * in, never the reverse.
   */
  public void allowSelfDelete() {
    this.permissions = permissions.withSelfDelete(true);
  }

  /** Reverses {@link #allowSelfDelete()}. */
  public void disallowSelfDelete() {
    this.permissions = permissions.withSelfDelete(false);
  }

  /**
   * Operator-controlled: checked by {@code DeviceTrustGate} before pausing a login for a
   * device-trust challenge. For a known-safe operator or service-style Account that should never be
   * paused while the Organization's {@code deviceTrustEnabled} policy stays on for everyone else.
   */
  public void enableDeviceTrustBypass() {
    this.permissions = permissions.withDeviceTrustBypass(true);
  }

  /** Reverses {@link #enableDeviceTrustBypass()}. */
  public void disableDeviceTrustBypass() {
    this.permissions = permissions.withDeviceTrustBypass(false);
  }

  /**
   * TD-FUT-034: replaces all three metadata tiers at once — always a full replace, never a partial
   * update, the "whole-object PUT" convention every multi-field admin mutation here follows. {@code
   * null} means empty/cleared for that tier.
   */
  public void setMetadata(
      final String publicMetadata, final String privateMetadata, final String unsafeMetadata) {
    this.metadata = new AccountMetadata(publicMetadata, privateMetadata, unsafeMetadata);
  }
}
