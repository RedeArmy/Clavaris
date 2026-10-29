package com.clavaris.organization.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
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
            true,
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
}
