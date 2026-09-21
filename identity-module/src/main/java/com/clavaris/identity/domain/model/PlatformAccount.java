package com.clavaris.identity.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * ADR-0012: a human, self-service identity at the Clavaris platform level itself — owns zero or
 * more {@code Organization}s (each {@code Organization} has exactly one owning {@code
 * PlatformAccount}, ADR-0012 §2). Deliberately separate from {@link Account} (a tenant end-user,
 * always scoped to exactly one {@code Organization}, ADR-0010) and from {@link
 * com.clavaris.identity.domain.model.PlatformAccountId}'s own sibling {@code PlatformClient} (a
 * machine credential, never a human) — three structurally distinct identities, never a shared type
 * with an optional/nullable field distinguishing them.
 *
 * <p>{@code email} is globally unique (no organization to scope it by, unlike {@code
 * accounts.(organization_id, email)}) — see the migration's own comment.
 *
 * <p>Same record-style-accessor PMD suppressions as {@link Account}, same rationale.
 */
@SuppressWarnings({
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.ShortVariable",
  "PMD.ShortMethodName",
  "PMD.TooManyMethods",
  "PMD.LongVariable"
})
public final class PlatformAccount {

  private final PlatformAccountId id;
  private final Email email;
  private final Instant createdAt;
  private Instant emailVerifiedAt;

  // No longer needs PMD.ImmutableField's own suppression once suspend()/reactivate() below
  // actually mutate this field outside the constructor (TD-FUT-031) — PMD only flags a field that
  // truly never changes after construction, which this one no longer is.
  private AccountStatus status;

  private PlatformPasswordCredential passwordCredential;

  // ADR-0026: same "Clerk manage-account parity" profile fields Account already has, extended to
  // this aggregate too — see PlatformAccountProfileController's own Javadoc for the self-service
  // page these back.
  private String firstName;
  private String lastName;
  private String pictureUrl;

  private PlatformAccount(
      final PlatformAccountId id,
      final Email email,
      final Instant createdAt,
      final AccountStatus status) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.email = Objects.requireNonNull(email, "email must not be null");
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    this.status = Objects.requireNonNull(status, "status must not be null");
  }

  /**
   * Registers a new platform account — same "no credential yet" intermediate state as {@link
   * Account#register}, for the same reason.
   */
  public static PlatformAccount register(final Email email) {
    return new PlatformAccount(
        PlatformAccountId.newId(), email, Instant.now(), AccountStatus.ACTIVE);
  }

  /** Same registration-time-only guard as {@link Account#attachPasswordCredential}. */
  public void attachPasswordCredential(final String passwordHash) {
    if (this.passwordCredential != null) {
      throw new IllegalStateException(
          "PlatformAccount " + id.value() + " already has a password credential attached");
    }
    this.passwordCredential = PlatformPasswordCredential.issue(id, passwordHash);
  }

  public static PlatformAccount reconstitute(
      final PlatformAccountId id,
      final Email email,
      final Instant createdAt,
      final Instant emailVerifiedAt,
      final AccountStatus status,
      final PlatformPasswordCredential passwordCredential) {
    return reconstitute(
        id, email, createdAt, emailVerifiedAt, status, passwordCredential, null, null, null);
  }

  /**
   * Full reconstitution including {@code firstName}/{@code lastName}/{@code pictureUrl} (ADR-0026)
   * — the persistence adapter's own rehydration path. The 6-arg overload above is kept, not
   * replaced, same "every existing caller that never touches the new fields stays unchanged"
   * precedent {@code Account.reconstitute}'s own multi-overload shape already establishes.
   */
  @SuppressWarnings("java:S107")
  public static PlatformAccount reconstitute(
      final PlatformAccountId id,
      final Email email,
      final Instant createdAt,
      final Instant emailVerifiedAt,
      final AccountStatus status,
      final PlatformPasswordCredential passwordCredential,
      final String firstName,
      final String lastName,
      final String pictureUrl) {
    final PlatformAccount account = new PlatformAccount(id, email, createdAt, status);
    account.emailVerifiedAt = emailVerifiedAt;
    account.passwordCredential = passwordCredential;
    account.firstName = firstName;
    account.lastName = lastName;
    account.pictureUrl = pictureUrl;
    return account;
  }

  /** Idempotent — same rationale as {@link Account#verifyEmail()}. */
  public void verifyEmail() {
    if (this.emailVerifiedAt == null) {
      this.emailVerifiedAt = Instant.now();
    }
  }

  /**
   * Same "reuse the existing row's id" discipline as {@link Account#resetPasswordCredential} — see
   * that method's own Javadoc for the real bug this avoids.
   */
  public void resetPasswordCredential(final String newPasswordHash) {
    if (this.passwordCredential == null) {
      throw new IllegalStateException(
          "PlatformAccount " + id.value() + " has no password credential to reset");
    }
    this.passwordCredential =
        PlatformPasswordCredential.reconstitute(
            this.passwordCredential.id(), id, newPasswordHash, Instant.now());
  }

  /**
   * TD-FUT-031: reversible ban, same state transition and same idempotency/DELETED-terminal
   * reasoning as {@link Account#suspend()} — {@code suspendplatformaccount.
   * SuspendPlatformAccountService}'s own job is only the transition itself, same "domain mutates
   * state, use case orchestrates side effects" split. {@code
   * AuthenticatePlatformAccountWithPasswordService} already rejects any non-{@link
   * AccountStatus#ACTIVE} account, so future logins are already blocked the moment this returns;
   * killing an already-live session is the calling service's own responsibility.
   */
  public void suspend() {
    if (this.status == AccountStatus.ACTIVE) {
      this.status = AccountStatus.SUSPENDED;
    }
  }

  /** Reverses {@link #suspend()} — same idempotency/DELETED-terminal reasoning. */
  public void reactivate() {
    if (this.status == AccountStatus.SUSPENDED) {
      this.status = AccountStatus.ACTIVE;
    }
  }

  public PlatformAccountId id() {
    return id;
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

  public Optional<PlatformPasswordCredential> passwordCredential() {
    return Optional.ofNullable(passwordCredential);
  }

  public Optional<String> firstName() {
    return Optional.ofNullable(firstName);
  }

  public Optional<String> lastName() {
    return Optional.ofNullable(lastName);
  }

  public Optional<String> pictureUrl() {
    return Optional.ofNullable(pictureUrl);
  }

  /**
   * ADR-0026: self-service "Update profile" name edit — always overwrites, unlike social capture.
   */
  public void updateProfile(final String firstName, final String lastName) {
    this.firstName = firstName;
    this.lastName = lastName;
  }

  /** Same rationale/shape as {@link Account#updateProfilePicture}. */
  public void updateProfilePicture(final String pictureUrl) {
    this.pictureUrl = Objects.requireNonNull(pictureUrl, "pictureUrl must not be null");
  }

  /** Same rationale/shape as {@link Account#removeProfilePicture}. */
  // PMD.NullAssignment: deliberately clears pictureUrl, same convention as
  // Account#removeProfilePicture's own identical suppression.
  @SuppressWarnings("PMD.NullAssignment")
  public void removeProfilePicture() {
    this.pictureUrl = null;
  }
}
