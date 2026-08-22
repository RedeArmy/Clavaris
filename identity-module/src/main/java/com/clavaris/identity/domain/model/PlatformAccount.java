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

  @SuppressWarnings("PMD.ImmutableField") // same rationale as Account.status
  private AccountStatus status;

  private PlatformPasswordCredential passwordCredential;

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
    final PlatformAccount account = new PlatformAccount(id, email, createdAt, status);
    account.emailVerifiedAt = emailVerifiedAt;
    account.passwordCredential = passwordCredential;
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
}
