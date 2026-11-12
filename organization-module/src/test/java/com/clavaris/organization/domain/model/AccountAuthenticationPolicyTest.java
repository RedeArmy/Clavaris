package com.clavaris.organization.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class AccountAuthenticationPolicyTest {

  private final UUID organizationId = UUID.randomUUID();

  @Test
  void defaultsMatchTodaysRealBehaviourBeforeThisFeatureExisted() {
    AccountAuthenticationPolicy policy = AccountAuthenticationPolicy.defaults(organizationId);

    assertThat(policy.organizationId()).isEqualTo(organizationId);
    assertThat(policy.emailVerificationRequiredAtSignIn()).isFalse();
    assertThat(policy.emailVerificationMethod()).isEqualTo(EmailVerificationMethod.LINK);
    assertThat(policy.emailCodeSignInEnabled()).isFalse();
    assertThat(policy.emailLinkSignInEnabled()).isFalse();
    assertThat(policy.usernameRequired()).isFalse();
    assertThat(policy.usernameSignInEnabled()).isFalse();
    assertThat(policy.passwordAtSignUpEnabled()).isTrue();
    assertThat(policy.deviceTrustEnabled()).isFalse();
  }

  @Test
  void defineCarriesEveryGivenField() {
    AccountAuthenticationPolicy policy =
        AccountAuthenticationPolicy.define(
            organizationId,
            new AccountAuthenticationSettings(
                true, EmailVerificationMethod.CODE, true, true, true, true, false, true, false));

    assertThat(policy.id()).isNotNull();
    assertThat(policy.organizationId()).isEqualTo(organizationId);
    assertThat(policy.emailVerificationRequiredAtSignIn()).isTrue();
    assertThat(policy.emailVerificationMethod()).isEqualTo(EmailVerificationMethod.CODE);
    assertThat(policy.emailCodeSignInEnabled()).isTrue();
    assertThat(policy.emailLinkSignInEnabled()).isTrue();
    assertThat(policy.usernameRequired()).isTrue();
    assertThat(policy.usernameSignInEnabled()).isTrue();
    assertThat(policy.passwordAtSignUpEnabled()).isFalse();
    assertThat(policy.deviceTrustEnabled()).isTrue();
    assertThat(policy.createdAt()).isEqualTo(policy.updatedAt());
  }

  // java:S2925: no injectable Clock, same convention RateLimitPolicyTest's own identical
  // suppression already documents.
  @SuppressWarnings("java:S2925")
  @Test
  void withPolicyKeepsTheSameIdAndCreatedAtButStampsAFreshUpdatedAt() throws InterruptedException {
    AccountAuthenticationPolicy original = AccountAuthenticationPolicy.defaults(organizationId);
    Thread.sleep(5);

    AccountAuthenticationPolicy updated =
        original.withPolicy(
            new AccountAuthenticationSettings(
                true, EmailVerificationMethod.BOTH, true, false, false, true, true, true, false));

    assertThat(updated.id()).isEqualTo(original.id());
    assertThat(updated.organizationId()).isEqualTo(original.organizationId());
    assertThat(updated.createdAt()).isEqualTo(original.createdAt());
    assertThat(updated.emailVerificationMethod()).isEqualTo(EmailVerificationMethod.BOTH);
    assertThat(updated.updatedAt())
        .as("re-tuning an existing policy must stamp a real, later updatedAt")
        .isAfter(original.updatedAt());
  }

  // TD-ARCH-047: each of the five security switches is a change to the policy. Built from a
  // persisted row with an old updatedAt rather than by sleeping, so the test does not depend on
  // the clock moving between two calls.
  @Test
  void eachSecuritySwitchStampsAFreshUpdatedAtAndKeepsTheRest() {
    final Instant old = Instant.parse("2026-01-02T00:00:00Z");
    final AccountAuthenticationPolicy persisted =
        AccountAuthenticationPolicy.reconstitute(
            UUID.randomUUID(),
            organizationId,
            AccountAuthenticationPolicy.defaults(organizationId).settings(),
            AccountAuthenticationPolicy.defaults(organizationId).security(),
            Instant.parse("2026-01-01T00:00:00Z"),
            old);

    final List<UnaryOperator<AccountAuthenticationPolicy>> flips =
        List.of(
            policy -> policy.withSelfServiceEmailChange(false),
            policy -> policy.withCodeAfterPasswordRequired(true),
            policy -> policy.withTotpEnabled(true),
            policy -> policy.withPasskeysEnabled(false),
            policy -> policy.withPasskeyOfferEnabled(false));

    for (final UnaryOperator<AccountAuthenticationPolicy> flip : flips) {
      final AccountAuthenticationPolicy flipped = flip.apply(persisted);

      assertThat(flipped.updatedAt()).isAfter(old);
      assertThat(flipped.createdAt()).isEqualTo(persisted.createdAt());
      assertThat(flipped.id()).isEqualTo(persisted.id());
      assertThat(flipped.settings()).isEqualTo(persisted.settings());
    }
  }

  @Test
  void reconstituteKeepsTheRealPersistedIdRatherThanMintingANewOne() {
    UUID persistedId = UUID.randomUUID();
    Instant persistedCreatedAt = Instant.parse("2026-01-01T00:00:00Z");
    Instant persistedUpdatedAt = Instant.parse("2026-01-02T00:00:00Z");

    AccountAuthenticationPolicy policy =
        AccountAuthenticationPolicy.reconstitute(
            persistedId,
            organizationId,
            new AccountAuthenticationSettings(
                true, EmailVerificationMethod.LINK, false, true, false, false, true, false, false),
            AccountSecuritySettings.defaults(),
            persistedCreatedAt,
            persistedUpdatedAt);

    assertThat(policy.id()).isEqualTo(persistedId);
    assertThat(policy.createdAt()).isEqualTo(persistedCreatedAt);
    assertThat(policy.updatedAt()).isEqualTo(persistedUpdatedAt);
  }

  // TD-FUT-052: on by default, switchable, and untouched by the other settings being re-set.
  @Test
  void selfServiceEmailChangeIsOnByDefaultCanBeSwitchedOffAndSurvivesAPolicyUpdate() {
    AccountAuthenticationPolicy defaults = AccountAuthenticationPolicy.defaults(organizationId);
    assertThat(defaults.selfServiceEmailChangeEnabled()).isTrue();

    AccountAuthenticationPolicy off = defaults.withSelfServiceEmailChange(false);
    assertThat(off.selfServiceEmailChangeEnabled()).isFalse();
    assertThat(off.id()).isEqualTo(defaults.id());

    AccountAuthenticationPolicy retuned =
        off.withPolicy(
            new AccountAuthenticationSettings(
                false,
                EmailVerificationMethod.LINK,
                false,
                false,
                false,
                false,
                true,
                false,
                false));
    assertThat(retuned.selfServiceEmailChangeEnabled())
        .as("re-tuning the other settings does not turn it back on")
        .isFalse();
  }

  // ADR-0035: off by default, switchable, and untouched by re-setting the other options.
  @Test
  void codeAfterPasswordIsOffByDefaultCanBeSwitchedOnAndSurvivesAPolicyUpdate() {
    AccountAuthenticationPolicy defaults = AccountAuthenticationPolicy.defaults(organizationId);
    assertThat(defaults.codeAfterPasswordRequired()).isFalse();

    AccountAuthenticationPolicy on = defaults.withCodeAfterPasswordRequired(true);
    assertThat(on.codeAfterPasswordRequired()).isTrue();

    AccountAuthenticationPolicy retuned = on.withPolicy(AccountAuthenticationSettings.defaults());
    assertThat(retuned.codeAfterPasswordRequired()).isTrue();
    assertThat(retuned.withSelfServiceEmailChange(false).codeAfterPasswordRequired()).isTrue();
  }

  @Test
  void theAuthenticatorAppIsOffByDefaultCanBeSwitchedOnAndSurvivesEveryOtherUpdate() {
    AccountAuthenticationPolicy defaults = AccountAuthenticationPolicy.defaults(organizationId);
    assertThat(defaults.totpEnabled()).isFalse();

    AccountAuthenticationPolicy on = defaults.withTotpEnabled(true);
    assertThat(on.totpEnabled()).isTrue();

    assertThat(on.withPolicy(AccountAuthenticationSettings.defaults()).totpEnabled()).isTrue();
    assertThat(on.withSelfServiceEmailChange(false).totpEnabled()).isTrue();
    assertThat(on.withCodeAfterPasswordRequired(true).totpEnabled()).isTrue();
    assertThat(on.withTotpEnabled(false).totpEnabled()).isFalse();
  }

  @Test
  void passkeysAndTheirOfferAreOnByDefaultCanBeSwitchedOffAndSurviveEveryOtherUpdate() {
    AccountAuthenticationPolicy defaults = AccountAuthenticationPolicy.defaults(organizationId);
    assertThat(defaults.passkeysEnabled()).isTrue();
    assertThat(defaults.passkeyOfferEnabled()).isTrue();

    AccountAuthenticationPolicy off =
        defaults.withPasskeysEnabled(false).withPasskeyOfferEnabled(false);
    assertThat(off.passkeysEnabled()).isFalse();
    assertThat(off.passkeyOfferEnabled()).isFalse();

    assertThat(off.withPolicy(AccountAuthenticationSettings.defaults()).passkeysEnabled())
        .isFalse();
    assertThat(off.withTotpEnabled(true).passkeyOfferEnabled()).isFalse();
    assertThat(off.withSelfServiceEmailChange(false).passkeysEnabled()).isFalse();
    assertThat(off.withCodeAfterPasswordRequired(true).passkeyOfferEnabled()).isFalse();
    assertThat(off.withPasskeysEnabled(true).passkeyOfferEnabled()).isFalse();
  }

  // The defaults are what an Organization that nobody has tuned behaves as: each switch is what the
  // product did before the switch existed.
  @Test
  void aNewPolicyStartsWithTheAuthenticatorAppOffAndPassKeysAndEmailChangeOn() {
    AccountAuthenticationPolicy fresh = AccountAuthenticationPolicy.defaults(organizationId);

    assertThat(fresh.security()).isEqualTo(AccountSecuritySettings.defaults());
    assertThat(fresh.totpEnabled()).isFalse();
    assertThat(fresh.codeAfterPasswordRequired()).isFalse();
    assertThat(fresh.passkeysEnabled()).as("passkeys were always on before the switch").isTrue();
    assertThat(fresh.passkeyOfferEnabled()).isTrue();
    assertThat(fresh.selfServiceEmailChangeEnabled()).isTrue();
  }

  // TD-ARCH-046: five booleans of one type, so a method that changes one must change only that one.
  @Test
  void eachSwitchIsChangedByItsOwnMethodAndNoOtherMoves() {
    final AccountSecuritySettings base = AccountSecuritySettings.defaults();

    assertThat(base.withSelfServiceEmailChange(false))
        .isEqualTo(new AccountSecuritySettings(false, false, false, true, true));
    assertThat(base.withCodeAfterPasswordRequired(true))
        .isEqualTo(new AccountSecuritySettings(true, true, false, true, true));
    assertThat(base.withTotpEnabled(true))
        .isEqualTo(new AccountSecuritySettings(true, false, true, true, true));
    assertThat(base.withPasskeysEnabled(false))
        .isEqualTo(new AccountSecuritySettings(true, false, false, false, true));
    assertThat(base.withPasskeyOfferEnabled(false))
        .isEqualTo(new AccountSecuritySettings(true, false, false, true, false));
  }

  @Test
  void aSwitchOnThePolicyChangesOnlyThatSwitchAndKeepsTheIdentityAndTheSignInOptions() {
    AccountAuthenticationPolicy original = AccountAuthenticationPolicy.defaults(organizationId);

    AccountAuthenticationPolicy changed = original.withTotpEnabled(true);

    assertThat(changed.totpEnabled()).isTrue();
    assertThat(changed.id()).isEqualTo(original.id());
    assertThat(changed.createdAt()).isEqualTo(original.createdAt());
    // The switch is a change to the policy, so it stamps a time (TD-ARCH-047; the test below pins
    // that against an old updatedAt).
    assertThat(changed.updatedAt()).isAfterOrEqualTo(original.updatedAt());
    assertThat(changed.settings()).isEqualTo(original.settings());
    assertThat(changed.security().withTotpEnabled(false)).isEqualTo(original.security());
  }

  // Changing the sign-in options must not drop the switches (the policy is replaced in place).
  @Test
  void replacingTheSignInOptionsKeepsEverySwitch() {
    AccountAuthenticationPolicy tuned =
        AccountAuthenticationPolicy.defaults(organizationId)
            .withTotpEnabled(true)
            .withPasskeysEnabled(false)
            .withSelfServiceEmailChange(false);

    AccountAuthenticationPolicy replaced =
        tuned.withPolicy(
            new AccountAuthenticationSettings(
                true, EmailVerificationMethod.CODE, true, false, false, true, false, true, false));

    assertThat(replaced.security()).isEqualTo(tuned.security());
  }
}
