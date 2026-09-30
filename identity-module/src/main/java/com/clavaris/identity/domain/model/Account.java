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
// PMD.AvoidDuplicateLiterals: the repeated string is "java:S107" itself, reused across every
// multi-field reconstitute() overload's own suppression — same rationale AccountRevocationCascade's
// own class-level suppression documents for its own literal-as-annotation-value case.
@SuppressWarnings({
  "PMD.TooManyMethods",
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.ShortVariable",
  "PMD.ShortMethodName",
  "PMD.LongVariable",
  "PMD.AvoidDuplicateLiterals",
  // TD-FUT-019's own 4 new public members (registerPendingApproval, approveRegistration,
  // rejectRegistration, plus the new reconstitute overload) pushed this class past the default
  // threshold — same "one mutator per use case that touches this aggregate is growth in the
  // right place" reasoning this class's own Javadoc already documents for PMD.TooManyMethods.
  "PMD.ExcessivePublicCount",
  // TD-FUT-034's own 3 new metadata fields pushed this class past the default field-count
  // threshold too - same "growth in the right place, not a signal to split the class" rationale
  // this class's own Javadoc already documents.
  "PMD.TooManyFields"
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

  // Clerk dashboard "Users" parity (SDE-III review, 2026-09-19): all four nullable, optional
  // profile attributes — email remains the one mandatory identity field (BR-ID-01), same posture
  // as username's own "optional secondary identifier" precedent (ADR-0024 §4). No dedicated value
  // types (unlike Email/Username): none of the four carries a real domain invariant beyond
  // "arbitrary display/contact text" — a validated PhoneNumber type would need real phone-number
  // parsing this codebase has no other reason to own yet.
  private String firstName;
  private String lastName;
  private String phoneNumber;

  // Updated by recordSignIn() on every successful password/social authentication — the "Last
  // signed In" column the dashboard Users tab shows, mirroring Clerk's own. Deliberately a plain
  // field mutated post-construction (like passwordResetRequiredAt), not folded into the
  // authentication use cases' own return value — every caller that authenticates an Account
  // already holds the aggregate and saves it back, the natural place for this side effect to live.
  private Instant lastSignedInAt;

  // ADR-0026: the Clavaris-owned avatar endpoint's own storage key/external reference for this
  // Account's uploaded or social-provider-sourced picture — null means "no picture set", not an
  // error; GetAccountAvatarService's own default-avatar fallback (initials + deterministic color)
  // covers that case, so this field is never required to be non-null anywhere downstream.
  private String pictureUrl;

  // Clerk "User permissions" parity (ADR-0026): both admin-controlled, both default false —
  // neither is a self-toggle, only PlatformAccountSettingsController (dashboard "Settings" tab)
  // ever mutates either. Plain booleans, not Optional-wrapped like the nullable fields above:
  // there is no "unset" state to represent, every Account genuinely has one of exactly two values
  // for each from the moment it's constructed.
  private boolean canDeleteOwnAccount;
  private boolean bypassesDeviceTrust;

  // TD-FUT-019 (gated self-registration): set exactly once, by approveRegistration()/
  // rejectRegistration(), the moment a PENDING_APPROVAL account is decided — null for every
  // account that was never gated in the first place (the overwhelming majority). rejectionReason
  // is only ever non-null alongside a REJECTED status; an approved account never sets it.
  private Instant registrationDecidedAt;
  private String registrationRejectionReason;

  // TD-FUT-034 (Clerk "Metadata" parity): three raw-JSON-text tiers Clavaris never interprets,
  // same "opaque, consumer-defined" posture OAuthClient.allowedScopes/WorkspaceRole.permissions
  // already establish — the shape is entirely the consuming application's own business. null means
  // never set, same convention as this class's other optional text fields.
  // publicMetadata: admin/backend-writable only (this codebase has no self-service metadata
  // endpoint), but semantically readable by anyone who can read this Account — never something
  // Clavaris itself must keep secret.
  // privateMetadata: admin/backend-writable AND admin/backend-readable only — never exposed
  // through any account-holder-facing surface.
  // unsafeMetadata: admin/backend-writable here (no end-user self-service surface exists yet) —
  // named "unsafe" per Clerk's own convention because a future self-service write path would let
  // the account holder set it directly, so no caller may ever treat its contents as trustworthy
  // for an authorization decision.
  private String publicMetadata;
  private String privateMetadata;
  private String unsafeMetadata;

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
   * Admin-initiated creation (dashboard "Users" tab, Clerk parity) — same aggregate, same
   * invariants as {@link #register(OrganizationId, Email)}, with the optional profile fields an
   * operator can fill in on the create-user form. {@code firstName}/{@code lastName}/{@code
   * phoneNumber} may each be {@code null} — every field on that form except email and password is
   * optional.
   */
  public static Account register(
      final OrganizationId organizationId,
      final Email email,
      final String firstName,
      final String lastName,
      final String phoneNumber) {
    final Account account = register(organizationId, email);
    account.firstName = firstName;
    account.lastName = lastName;
    account.phoneNumber = phoneNumber;
    return account;
  }

  /**
   * TD-FUT-019: same as {@link #register(OrganizationId, Email)}, but for an Organization whose own
   * {@code AccountAuthenticationPolicy.selfRegistrationRequiresApproval()} is on — the account
   * exists and can be found/administered, but cannot sign in ({@code AuthenticateWith*Service}
   * rejects any non-{@code ACTIVE} account, same unconditional gate {@code SUSPENDED}/{@code
   * BANNED} already rely on) until {@link #approveRegistration()} runs. The caller is still
   * responsible for attaching a credential before persisting (BR-ID-02) — {@code PENDING_APPROVAL}
   * governs usability, not the same-aggregate-invariant this class always enforces regardless of
   * status.
   */
  public static Account registerPendingApproval(
      final OrganizationId organizationId, final Email email) {
    return new Account(
        AccountId.newId(), organizationId, email, Instant.now(), AccountStatus.PENDING_APPROVAL);
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
    return reconstitute(
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
        null,
        null,
        null,
        null);
  }

  /**
   * Full reconstitution including the Clerk-parity profile/last-sign-in fields (SDE-III review,
   * 2026-09-19) — the persistence adapter's own rehydration path. The 9-arg overload above is kept,
   * not replaced, so every existing caller that never touches these four fields (the overwhelming
   * majority — see this class's own commit history) stays unchanged.
   */
  @SuppressWarnings({"java:S107", "PMD.ExcessiveParameterList"})
  public static Account reconstitute(
      final AccountId id,
      final OrganizationId organizationId,
      final Email email,
      final Instant createdAt,
      final Instant emailVerifiedAt,
      final AccountStatus status,
      final PasswordCredential passwordCredential,
      final Username username,
      final Instant passwordResetRequiredAt,
      final String firstName,
      final String lastName,
      final String phoneNumber,
      final Instant lastSignedInAt) {
    return reconstitute(
        id,
        organizationId,
        email,
        createdAt,
        emailVerifiedAt,
        status,
        passwordCredential,
        username,
        passwordResetRequiredAt,
        firstName,
        lastName,
        phoneNumber,
        lastSignedInAt,
        null);
  }

  /**
   * Full reconstitution including {@code pictureUrl} (ADR-0026) — the persistence adapter's own
   * rehydration path. The 13-arg overload above is kept, not replaced, same "every existing caller
   * that never touches the new field stays unchanged" precedent that overload's own Javadoc already
   * documents for the 9-arg one below it.
   */
  @SuppressWarnings({"java:S107", "PMD.ExcessiveParameterList"})
  public static Account reconstitute(
      final AccountId id,
      final OrganizationId organizationId,
      final Email email,
      final Instant createdAt,
      final Instant emailVerifiedAt,
      final AccountStatus status,
      final PasswordCredential passwordCredential,
      final Username username,
      final Instant passwordResetRequiredAt,
      final String firstName,
      final String lastName,
      final String phoneNumber,
      final Instant lastSignedInAt,
      final String pictureUrl) {
    return reconstitute(
        id,
        organizationId,
        email,
        createdAt,
        emailVerifiedAt,
        status,
        passwordCredential,
        username,
        passwordResetRequiredAt,
        firstName,
        lastName,
        phoneNumber,
        lastSignedInAt,
        pictureUrl,
        false,
        false);
  }

  /**
   * Full reconstitution including {@code canDeleteOwnAccount}/{@code bypassesDeviceTrust}
   * (ADR-0026, Clerk "User permissions" parity) — the persistence adapter's own rehydration path.
   * The 14-arg overload above is kept, not replaced, same "every existing caller that never touches
   * the new fields stays unchanged" precedent that overload's own Javadoc already documents for the
   * 13-arg one below it.
   */
  @SuppressWarnings({"java:S107", "PMD.ExcessiveParameterList"})
  public static Account reconstitute(
      final AccountId id,
      final OrganizationId organizationId,
      final Email email,
      final Instant createdAt,
      final Instant emailVerifiedAt,
      final AccountStatus status,
      final PasswordCredential passwordCredential,
      final Username username,
      final Instant passwordResetRequiredAt,
      final String firstName,
      final String lastName,
      final String phoneNumber,
      final Instant lastSignedInAt,
      final String pictureUrl,
      final boolean canDeleteOwnAccount,
      final boolean bypassesDeviceTrust) {
    final Account account = new Account(id, organizationId, email, createdAt, status);
    account.emailVerifiedAt = emailVerifiedAt;
    account.passwordCredential = passwordCredential;
    account.username = username;
    account.passwordResetRequiredAt = passwordResetRequiredAt;
    account.firstName = firstName;
    account.lastName = lastName;
    account.phoneNumber = phoneNumber;
    account.lastSignedInAt = lastSignedInAt;
    account.pictureUrl = pictureUrl;
    account.canDeleteOwnAccount = canDeleteOwnAccount;
    account.bypassesDeviceTrust = bypassesDeviceTrust;
    return account;
  }

  /**
   * Full reconstitution including {@code registrationDecidedAt}/{@code registrationRejectionReason}
   * (TD-FUT-019) — the persistence adapter's own rehydration path. The 16-arg overload above is
   * kept, not replaced, same "every existing caller that never touches the new fields stays
   * unchanged" precedent that overload's own Javadoc already documents for the one below it.
   */
  @SuppressWarnings({"java:S107", "PMD.ExcessiveParameterList"})
  public static Account reconstitute(
      final AccountId id,
      final OrganizationId organizationId,
      final Email email,
      final Instant createdAt,
      final Instant emailVerifiedAt,
      final AccountStatus status,
      final PasswordCredential passwordCredential,
      final Username username,
      final Instant passwordResetRequiredAt,
      final String firstName,
      final String lastName,
      final String phoneNumber,
      final Instant lastSignedInAt,
      final String pictureUrl,
      final boolean canDeleteOwnAccount,
      final boolean bypassesDeviceTrust,
      final Instant registrationDecidedAt,
      final String registrationRejectionReason) {
    final Account account =
        reconstitute(
            id,
            organizationId,
            email,
            createdAt,
            emailVerifiedAt,
            status,
            passwordCredential,
            username,
            passwordResetRequiredAt,
            firstName,
            lastName,
            phoneNumber,
            lastSignedInAt,
            pictureUrl,
            canDeleteOwnAccount,
            bypassesDeviceTrust);
    account.registrationDecidedAt = registrationDecidedAt;
    account.registrationRejectionReason = registrationRejectionReason;
    return account;
  }

  /**
   * Full reconstitution including {@code publicMetadata}/{@code privateMetadata}/{@code
   * unsafeMetadata} (TD-FUT-034) — the persistence adapter's own rehydration path. The 18-arg
   * overload above is kept, not replaced, same "every existing caller that never touches the new
   * fields stays unchanged" precedent that overload's own Javadoc already documents for the one
   * below it.
   */
  @SuppressWarnings({"java:S107", "PMD.ExcessiveParameterList"})
  public static Account reconstitute(
      final AccountId id,
      final OrganizationId organizationId,
      final Email email,
      final Instant createdAt,
      final Instant emailVerifiedAt,
      final AccountStatus status,
      final PasswordCredential passwordCredential,
      final Username username,
      final Instant passwordResetRequiredAt,
      final String firstName,
      final String lastName,
      final String phoneNumber,
      final Instant lastSignedInAt,
      final String pictureUrl,
      final boolean canDeleteOwnAccount,
      final boolean bypassesDeviceTrust,
      final Instant registrationDecidedAt,
      final String registrationRejectionReason,
      final String publicMetadata,
      final String privateMetadata,
      final String unsafeMetadata) {
    final Account account =
        reconstitute(
            id,
            organizationId,
            email,
            createdAt,
            emailVerifiedAt,
            status,
            passwordCredential,
            username,
            passwordResetRequiredAt,
            firstName,
            lastName,
            phoneNumber,
            lastSignedInAt,
            pictureUrl,
            canDeleteOwnAccount,
            bypassesDeviceTrust,
            registrationDecidedAt,
            registrationRejectionReason);
    account.publicMetadata = publicMetadata;
    account.privateMetadata = privateMetadata;
    account.unsafeMetadata = unsafeMetadata;
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

  public Optional<String> firstName() {
    return Optional.ofNullable(firstName);
  }

  public Optional<String> lastName() {
    return Optional.ofNullable(lastName);
  }

  public Optional<String> phoneNumber() {
    return Optional.ofNullable(phoneNumber);
  }

  public Optional<Instant> lastSignedInAt() {
    return Optional.ofNullable(lastSignedInAt);
  }

  public Optional<String> pictureUrl() {
    return Optional.ofNullable(pictureUrl);
  }

  public boolean canDeleteOwnAccount() {
    return canDeleteOwnAccount;
  }

  public boolean bypassesDeviceTrust() {
    return bypassesDeviceTrust;
  }

  public Optional<Instant> registrationDecidedAt() {
    return Optional.ofNullable(registrationDecidedAt);
  }

  public Optional<String> registrationRejectionReason() {
    return Optional.ofNullable(registrationRejectionReason);
  }

  public Optional<String> publicMetadata() {
    return Optional.ofNullable(publicMetadata);
  }

  public Optional<String> privateMetadata() {
    return Optional.ofNullable(privateMetadata);
  }

  public Optional<String> unsafeMetadata() {
    return Optional.ofNullable(unsafeMetadata);
  }

  /**
   * Called by every successful authentication path (password, social) right before the account is
   * saved back — the dashboard Users tab's "Last signed In" column (Clerk parity). Unconditional,
   * not idempotent-guarded like {@link #verifyEmail()}: unlike a one-time confirmation, this is
   * meant to move forward on every single sign-in, not just the first.
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

  /**
   * SDE-III review, 2026-09-19 — Clerk dashboard "Users" parity: a deliberately separate action
   * from {@link #suspend()}, not an alias — see {@link AccountStatus}'s own Javadoc for why. Same
   * idempotent/terminal-status handling; only transitions out of {@code ACTIVE}, never out of
   * {@code SUSPENDED} (an account already suspended must be explicitly reactivated first — the two
   * states don't silently convert into one another).
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
   * TD-FUT-019: the operator-dashboard or consuming-application-backend approval decision — only
   * ever transitions a {@code PENDING_APPROVAL} account, idempotent/no-op from any other status,
   * same defensive shape {@link #suspend()}/{@link #ban()} already establish. The real precondition
   * ("was this genuinely pending") is the calling use case's own job to check and report as a typed
   * error before calling this — this method is the safety net, not the primary signal.
   */
  public void approveRegistration() {
    if (this.status == AccountStatus.PENDING_APPROVAL) {
      this.status = AccountStatus.ACTIVE;
      this.registrationDecidedAt = Instant.now();
    }
  }

  /**
   * TD-FUT-019: the rejection counterpart to {@link #approveRegistration()} — same idempotent,
   * PENDING_APPROVAL-only transition shape. {@code reason} may be {@code null} (no reason given);
   * never required, since the consuming application's own callback is not guaranteed to supply one.
   */
  public void rejectRegistration(final String reason) {
    if (this.status == AccountStatus.PENDING_APPROVAL) {
      this.status = AccountStatus.REJECTED;
      this.registrationDecidedAt = Instant.now();
      this.registrationRejectionReason = reason;
    }
  }

  /**
   * ADR-0026: self-service picture upload (also used by an operator-initiated change) — always
   * overwrites, unlike {@link #applySocialProviderProfile}, since a deliberate replace is exactly
   * what this method is for.
   */
  public void updateProfilePicture(final String pictureUrl) {
    this.pictureUrl = Objects.requireNonNull(pictureUrl, "pictureUrl must not be null");
  }

  /** Reverts to the generated-initials default avatar (ADR-0026) — idempotent. */
  // PMD.NullAssignment: deliberately clears pictureUrl, same convention as
  // attachPasswordCredential's own identical suppression.
  @SuppressWarnings("PMD.NullAssignment")
  public void removeProfilePicture() {
    this.pictureUrl = null;
  }

  /**
   * ADR-0026 / BR-ID-09 sibling decision: captures {@code firstName}/{@code lastName}/{@code
   * pictureUrl} from a social provider's own profile claims — called exactly once, immediately
   * after {@link #register(OrganizationId, Email)} in {@code
   * AuthenticateWithSocialProviderService}'s brand-new-signup branch, never on a returning social
   * login. Deliberately non-destructive (only fills a field that's still {@code null}) rather than
   * an unconditional overwrite — at the one real call site every field is already null (a brand-new
   * aggregate), but a non-destructive guard is the correct contract to publish regardless of that
   * call site's own current behavior, not an incidental effect of it.
   */
  public void applySocialProviderProfile(
      final String firstName, final String lastName, final String pictureUrl) {
    if (this.firstName == null) {
      this.firstName = firstName;
    }
    if (this.lastName == null) {
      this.lastName = lastName;
    }
    if (this.pictureUrl == null) {
      this.pictureUrl = pictureUrl;
    }
  }

  /**
   * ADR-0026: self-service (via the "Update profile" page) or operator-initiated name edit — always
   * overwrites, same "deliberate replace" posture as {@link #updateProfilePicture}. Not {@code
   * Optional}-typed parameters: a blank submitted value is this method's own caller's job to
   * normalize to {@code null} before calling (the same convention every controller in this codebase
   * already follows for optional text fields), not something the domain method itself should
   * special-case.
   */
  public void updateProfile(final String firstName, final String lastName) {
    this.firstName = firstName;
    this.lastName = lastName;
  }

  /**
   * SDE-III review, 2026-09-22 — operator-driven edit (dashboard "View Profile" > Profile tab,
   * {@code PlatformAccountProfileAdminController}); unlike {@link #assignUsername}, phone number
   * carries no uniqueness constraint of its own — this method itself always overwrites, same
   * "deliberate replace" posture as {@link #updateProfile}/{@link #updateProfilePicture}. Its own
   * caller ({@code UpdateAccountProfileService}) chooses to only ever invoke this once, mirroring
   * username's own "view-only once set" UX at the caller's explicit request — a use-case-level
   * policy, not a domain invariant this method itself enforces.
   */
  public void updatePhoneNumber(final String phoneNumber) {
    this.phoneNumber = phoneNumber;
  }

  /**
   * Clerk "User permissions" parity — operator-controlled (dashboard "Settings" tab), gates {@code
   * DeleteOwnAccountUseCase}'s own self-service "Delete account" action. Defaults to {@code false}
   * (see the field's own comment) — an Organization opts a specific Account into this, it is never
   * on by default.
   */
  public void allowSelfDelete() {
    this.canDeleteOwnAccount = true;
  }

  /** Reverses {@link #allowSelfDelete()}. */
  public void disallowSelfDelete() {
    this.canDeleteOwnAccount = false;
  }

  /**
   * Clerk "User permissions" parity — operator-controlled, checked by {@link
   * com.clavaris.identity.infrastructure.adapter.in.web.DeviceTrustGate} (via {@code
   * PrimaryFactorLoginCompletion}/its own two direct callers) before pausing a login for a
   * device-trust challenge. Legitimate use: a known-safe operator or service-style Account that
   * should never be paused, even while the owning Organization's own {@code deviceTrustEnabled}
   * policy stays on for every other Account.
   */
  public void enableDeviceTrustBypass() {
    this.bypassesDeviceTrust = true;
  }

  /** Reverses {@link #enableDeviceTrustBypass()}. */
  public void disableDeviceTrustBypass() {
    this.bypassesDeviceTrust = false;
  }

  /**
   * TD-FUT-034 (Clerk "Metadata" parity) — replaces all three tiers at once, always a full replace,
   * never a partial update, same "whole-object PUT" convention every other multi-field admin
   * mutation in this codebase already follows (e.g. {@code
   * SetAccountAuthenticationPolicyController}) rather than trying to distinguish "leave this tier
   * alone" from "clear it" for a given argument. {@code null} means empty/cleared for that tier,
   * same convention this class's own nullable text fields already use. Syntactic JSON validity is
   * the caller's own job (the web/use-case layer, before this method ever runs) — this class stays
   * free of a JSON library dependency, same "domain/ depends on nothing" rule every other method
   * here already follows.
   */
  public void setMetadata(
      final String publicMetadata, final String privateMetadata, final String unsafeMetadata) {
    this.publicMetadata = publicMetadata;
    this.privateMetadata = privateMetadata;
    this.unsafeMetadata = unsafeMetadata;
  }
}
