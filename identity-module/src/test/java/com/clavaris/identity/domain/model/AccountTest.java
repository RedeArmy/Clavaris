package com.clavaris.identity.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountTest {

  private final OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
  private final Email email = new Email("new-user@example.com");

  @Test
  void registerCreatesAnActiveAccountWithNoCredentialYet() {
    // BR-ID-02: registration itself never attaches a credential — the use case does that as a
    // separate, explicit step, so "an Account with no credential" is a real, valid intermediate
    // state the factory can produce, not something a caller could accidentally skip.
    Account account = Account.register(organizationId, email);

    assertThat(account.organizationId()).isEqualTo(organizationId);
    assertThat(account.email()).isEqualTo(email);
    assertThat(account.status()).isEqualTo(AccountStatus.ACTIVE);
    assertThat(account.emailVerifiedAt()).isEmpty();
    assertThat(account.passwordCredential()).isEmpty();
  }

  @Test
  void eachRegistrationGetsAUniqueId() {
    Account first = Account.register(organizationId, email);
    Account second = Account.register(organizationId, new Email("someone-else@example.com"));

    assertThat(first.id()).isNotEqualTo(second.id());
  }

  @Test
  void attachPasswordCredentialGivesTheAccountAWorkingAuthMethod() {
    Account account = Account.register(organizationId, email);

    account.attachPasswordCredential("argon2id$hashed-value");

    assertThat(account.passwordCredential()).isPresent();
    assertThat(account.passwordCredential().orElseThrow().accountId()).isEqualTo(account.id());
    assertThat(account.passwordCredential().orElseThrow().passwordHash())
        .isEqualTo("argon2id$hashed-value");
  }

  @Test
  void cannotAttachASecondPasswordCredentialAtRegistrationTime() {
    // Deliberately not a password-change flow (that's a separate future use case with its own
    // invariants, e.g. requiring the current password) — attaching twice here is always a bug.
    Account account = Account.register(organizationId, email);
    account.attachPasswordCredential("first-hash");

    assertThatIllegalStateException()
        .isThrownBy(() -> account.attachPasswordCredential("second-hash"));
  }

  @Test
  void reconstitutePreservesTheRealPersistedFieldsAndAttachedCredential() {
    AccountId id = new AccountId(UUID.randomUUID());
    Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
    Instant emailVerifiedAt = Instant.parse("2026-01-02T00:00:00Z");
    PasswordCredential credential =
        PasswordCredential.reconstitute(
            UUID.randomUUID(), id, "argon2id$stored-hash", Instant.parse("2026-01-03T00:00:00Z"));

    Account account =
        Account.reconstitute(
            id,
            organizationId,
            email,
            createdAt,
            emailVerifiedAt,
            AccountStatus.SUSPENDED,
            credential,
            null,
            null);

    assertThat(account.id()).isEqualTo(id);
    assertThat(account.organizationId()).isEqualTo(organizationId);
    assertThat(account.email()).isEqualTo(email);
    assertThat(account.createdAt()).isEqualTo(createdAt);
    assertThat(account.emailVerifiedAt()).contains(emailVerifiedAt);
    assertThat(account.status()).isEqualTo(AccountStatus.SUSPENDED);
    assertThat(account.passwordCredential()).contains(credential);
  }

  @Test
  void verifyEmailSetsTheTimestampOnceAndIsIdempotentOnASecondCall() {
    Account account = Account.register(organizationId, email);

    account.verifyEmail();
    Instant firstVerifiedAt = account.emailVerifiedAt().orElseThrow();
    account.verifyEmail();

    // Idempotent, not "re-verify resets the clock": a second confirm (e.g. a stale duplicate
    // click) must not disturb the original verification timestamp.
    assertThat(account.emailVerifiedAt()).contains(firstVerifiedAt);
  }

  @Test
  void resetPasswordCredentialUpdatesTheExistingCredentialsHashInPlace() {
    Account account = Account.register(organizationId, email);
    account.attachPasswordCredential("old-hash");
    PasswordCredential original = account.passwordCredential().orElseThrow();

    account.resetPasswordCredential("new-hash");

    PasswordCredential replaced = account.passwordCredential().orElseThrow();
    assertThat(replaced.passwordHash()).isEqualTo("new-hash");
    assertThat(replaced.accountId()).isEqualTo(account.id());
    assertThat(replaced).isNotSameAs(original);
    // Regression check for a real bug a live integration test caught: reusing the SAME id (not
    // minting a fresh one) is what makes JpaAccountRepository#save persist this as an UPDATE to
    // the one password_credentials row an account may ever have, not an INSERT of a second row
    // that violates the table's own UNIQUE(account_id) constraint.
    assertThat(replaced.id()).isEqualTo(original.id());
  }

  // Clerk "session tasks" parity.
  @Test
  void resetPasswordCredentialClearsAnOutstandingPasswordResetRequirement() {
    Account account = Account.register(organizationId, email);
    account.attachPasswordCredential("old-hash");
    account.requirePasswordReset();
    assertThat(account.passwordResetRequiredAt()).isPresent();

    account.resetPasswordCredential("new-hash");

    assertThat(account.passwordResetRequiredAt()).isEmpty();
  }

  @Test
  void attachPasswordCredentialClearsAnOutstandingPasswordResetRequirement() {
    // Covers a password-optional account (ADR-0024 §5) forced to set its very first password
    // rather than rotate an existing one.
    Account account = Account.register(organizationId, email);
    account.requirePasswordReset();
    assertThat(account.passwordResetRequiredAt()).isPresent();

    account.attachPasswordCredential("first-hash");

    assertThat(account.passwordResetRequiredAt()).isEmpty();
  }

  @Test
  void requirePasswordResetSetsATimestamp() {
    Account account = Account.register(organizationId, email);

    account.requirePasswordReset();

    assertThat(account.passwordResetRequiredAt()).isPresent();
  }

  // java:S2925: Account has no injectable Clock, same Instant.now()-direct convention as every
  // other domain entity in this codebase — see RateLimitPolicyTest's own identical rationale.
  @SuppressWarnings("java:S2925")
  @Test
  void requirePasswordResetIsIdempotent_doesNotStampAFreshTimestampOnASecondCall()
      throws InterruptedException {
    Account account = Account.register(organizationId, email);
    account.requirePasswordReset();
    java.time.Instant original = account.passwordResetRequiredAt().orElseThrow();
    Thread.sleep(5);

    account.requirePasswordReset();

    assertThat(account.passwordResetRequiredAt()).contains(original);
  }

  @Test
  void resetPasswordCredentialRejectsAnAccountWithNoExistingCredential() {
    // BR-ID-04's reset flow presupposes something to reset — a social-only account (once
    // SocialIdentity exists) has no password to replace; the use case must reject this before it
    // ever reaches here, not silently attach a first credential via the reset path.
    Account account = Account.register(organizationId, email);

    assertThatIllegalStateException().isThrownBy(() -> account.resetPasswordCredential("hash"));
  }

  @Test
  void reconstituteAllowsANullPasswordCredential() {
    // BR-ID-02 still guarantees at least one auth method exists — just not necessarily this one
    // (a social-only account, once SocialIdentity exists, is the real case this covers).
    Account account =
        Account.reconstitute(
            new AccountId(UUID.randomUUID()),
            organizationId,
            email,
            Instant.now(),
            null,
            AccountStatus.ACTIVE,
            null,
            null,
            null);

    assertThat(account.passwordCredential()).isEmpty();
  }

  @Test
  void suspendTransitionsAnActiveAccountToSuspended() {
    Account account = Account.register(organizationId, email);

    account.suspend();

    assertThat(account.status()).isEqualTo(AccountStatus.SUSPENDED);
  }

  @Test
  void suspendIsIdempotent_reSuspendingAnAlreadySuspendedAccountIsANoOp() {
    Account account = Account.register(organizationId, email);
    account.suspend();

    account.suspend();

    assertThat(account.status()).isEqualTo(AccountStatus.SUSPENDED);
  }

  @Test
  void suspendDoesNothingToADeletedAccount_terminalStatusNeverReversed() {
    Account account =
        Account.reconstitute(
            new AccountId(UUID.randomUUID()),
            organizationId,
            email,
            Instant.now(),
            null,
            AccountStatus.DELETED,
            null,
            null,
            null);

    account.suspend();

    assertThat(account.status()).isEqualTo(AccountStatus.DELETED);
  }

  @Test
  void reactivateTransitionsASuspendedAccountBackToActive() {
    Account account = Account.register(organizationId, email);
    account.suspend();

    account.reactivate();

    assertThat(account.status()).isEqualTo(AccountStatus.ACTIVE);
  }

  @Test
  void reactivateIsIdempotent_reactivatingAnAlreadyActiveAccountIsANoOp() {
    Account account = Account.register(organizationId, email);

    account.reactivate();

    assertThat(account.status()).isEqualTo(AccountStatus.ACTIVE);
  }

  @Test
  void reactivateDoesNothingToADeletedAccount_terminalStatusNeverReversed() {
    Account account =
        Account.reconstitute(
            new AccountId(UUID.randomUUID()),
            organizationId,
            email,
            Instant.now(),
            null,
            AccountStatus.DELETED,
            null,
            null,
            null);

    account.reactivate();

    assertThat(account.status()).isEqualTo(AccountStatus.DELETED);
  }

  // SDE-III review, 2026-09-19 — Clerk dashboard "Users" parity: ban()/unban() mirror
  // suspend()/reactivate()'s own tests exactly, plus one proving the two states never silently
  // convert into one another (AccountStatus's own Javadoc).
  @Test
  void banTransitionsAnActiveAccountToBanned() {
    Account account = Account.register(organizationId, email);

    account.ban();

    assertThat(account.status()).isEqualTo(AccountStatus.BANNED);
  }

  @Test
  void banIsIdempotent_reBanningAnAlreadyBannedAccountIsANoOp() {
    Account account = Account.register(organizationId, email);
    account.ban();

    account.ban();

    assertThat(account.status()).isEqualTo(AccountStatus.BANNED);
  }

  @Test
  void banDoesNothingToADeletedAccount_terminalStatusNeverReversed() {
    Account account =
        Account.reconstitute(
            new AccountId(UUID.randomUUID()),
            organizationId,
            email,
            Instant.now(),
            null,
            AccountStatus.DELETED,
            null,
            null,
            null);

    account.ban();

    assertThat(account.status()).isEqualTo(AccountStatus.DELETED);
  }

  @Test
  void banDoesNothingToASuspendedAccount_theTwoStatesNeverSilentlyConvert() {
    Account account = Account.register(organizationId, email);
    account.suspend();

    account.ban();

    assertThat(account.status()).isEqualTo(AccountStatus.SUSPENDED);
  }

  @Test
  void unbanTransitionsABannedAccountBackToActive() {
    Account account = Account.register(organizationId, email);
    account.ban();

    account.unban();

    assertThat(account.status()).isEqualTo(AccountStatus.ACTIVE);
  }

  @Test
  void unbanIsIdempotent_unbanningAnAlreadyActiveAccountIsANoOp() {
    Account account = Account.register(organizationId, email);

    account.unban();

    assertThat(account.status()).isEqualTo(AccountStatus.ACTIVE);
  }

  @Test
  void unbanDoesNothingToASuspendedAccount_theTwoStatesNeverSilentlyConvert() {
    Account account = Account.register(organizationId, email);
    account.suspend();

    account.unban();

    assertThat(account.status()).isEqualTo(AccountStatus.SUSPENDED);
  }

  @Test
  void newAccountHasNoPictureUrl() {
    Account account = Account.register(organizationId, email);

    assertThat(account.pictureUrl()).isEmpty();
  }

  @Test
  void updateProfilePictureSetsThePictureUrl() {
    Account account = Account.register(organizationId, email);

    account.updateProfilePicture("https://example.com/avatar.png");

    assertThat(account.pictureUrl()).contains("https://example.com/avatar.png");
  }

  @Test
  void updateProfilePictureOverwritesAnExistingOne_deliberateReplace() {
    Account account = Account.register(organizationId, email);
    account.updateProfilePicture("https://example.com/old.png");

    account.updateProfilePicture("https://example.com/new.png");

    assertThat(account.pictureUrl()).contains("https://example.com/new.png");
  }

  @Test
  void removeProfilePictureRevertsToTheGeneratedInitialsDefault() {
    Account account = Account.register(organizationId, email);
    account.updateProfilePicture("https://example.com/avatar.png");

    account.removeProfilePicture();

    assertThat(account.pictureUrl()).isEmpty();
  }

  @Test
  void removeProfilePictureIsIdempotent() {
    Account account = Account.register(organizationId, email);

    account.removeProfilePicture();

    assertThat(account.pictureUrl()).isEmpty();
  }

  @Test
  void applySocialProviderProfileFillsNameAndPictureOnABrandNewAccount() {
    Account account = Account.register(organizationId, email);

    account.applySocialProviderProfile("Ada", "Lovelace", "https://example.com/avatar.png");

    assertThat(account.firstName()).contains("Ada");
    assertThat(account.lastName()).contains("Lovelace");
    assertThat(account.pictureUrl()).contains("https://example.com/avatar.png");
  }

  @Test
  void updateProfileOverwritesFirstAndLastName() {
    Account account = Account.register(organizationId, email, "Old", "Name", null);

    account.updateProfile("New", "Name2");

    assertThat(account.firstName()).contains("New");
    assertThat(account.lastName()).contains("Name2");
  }

  @Test
  void newAccountCannotDeleteItsOwnAccountByDefault() {
    Account account = Account.register(organizationId, email);

    assertThat(account.canDeleteOwnAccount()).isFalse();
  }

  @Test
  void allowSelfDeleteAndDisallowSelfDeleteToggleTheFlag() {
    Account account = Account.register(organizationId, email);

    account.allowSelfDelete();
    assertThat(account.canDeleteOwnAccount()).isTrue();

    account.disallowSelfDelete();
    assertThat(account.canDeleteOwnAccount()).isFalse();
  }

  @Test
  void newAccountDoesNotBypassDeviceTrustByDefault() {
    Account account = Account.register(organizationId, email);

    assertThat(account.bypassesDeviceTrust()).isFalse();
  }

  @Test
  void enableDeviceTrustBypassAndDisableDeviceTrustBypassToggleTheFlag() {
    Account account = Account.register(organizationId, email);

    account.enableDeviceTrustBypass();
    assertThat(account.bypassesDeviceTrust()).isTrue();

    account.disableDeviceTrustBypass();
    assertThat(account.bypassesDeviceTrust()).isFalse();
  }

  @Test
  void applySocialProviderProfileNeverOverwritesAnAlreadySetField_neverResyncsAfterRegistration() {
    Account account = Account.register(organizationId, email, "Grace", "Hopper", null);
    account.updateProfilePicture("https://example.com/manual-upload.png");

    account.applySocialProviderProfile(
        "Different", "Name", "https://example.com/social-provider.png");

    assertThat(account.firstName()).contains("Grace");
    assertThat(account.lastName()).contains("Hopper");
    assertThat(account.pictureUrl()).contains("https://example.com/manual-upload.png");
  }
}
