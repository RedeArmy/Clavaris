package com.clavaris.organization.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SessionPolicyTest {

  private final UUID organizationId = UUID.randomUUID();

  @Test
  void defaultsReturnsClerksOwnRealDefaultsForAnUnconfiguredOrganization() {
    SessionPolicy policy = SessionPolicy.defaults(organizationId);

    assertThat(policy.organizationId()).isEqualTo(organizationId);
    assertThat(policy.maximumLifetimeMinutes()).isEqualTo(10_080);
    assertThat(policy.inactivityTimeoutMinutes()).isEqualTo(10_080);
    assertThat(policy.reverificationWindowMinutes()).isEqualTo(10);
    assertThat(policy.multiSessionHandlingEnabled()).isTrue();
  }

  @Test
  void defineCarriesTheGivenFields() {
    SessionPolicy policy = SessionPolicy.define(organizationId, 20_160, 1_440, 5, false, 0);

    assertThat(policy.organizationId()).isEqualTo(organizationId);
    assertThat(policy.maximumLifetimeMinutes()).isEqualTo(20_160);
    assertThat(policy.inactivityTimeoutMinutes()).isEqualTo(1_440);
    assertThat(policy.reverificationWindowMinutes()).isEqualTo(5);
    assertThat(policy.multiSessionHandlingEnabled()).isFalse();
    assertThat(policy.id()).isNotNull();
    assertThat(policy.createdAt()).isNotNull();
    assertThat(policy.updatedAt()).isEqualTo(policy.createdAt());
  }

  @Test
  void acceptsEveryBoundaryValueOfEveryRange() {
    // 5 min / 5 min / 1 min lower bounds, 10 years / 1 year / 10 min upper bounds — same
    // deliberate-boundary-inclusion the user's own spec names explicitly ("Accepts between X and
    // Y").
    SessionPolicy lower = SessionPolicy.define(organizationId, 5, 5, 1, true, 0);
    SessionPolicy upper = SessionPolicy.define(organizationId, 5_256_000, 525_600, 10, true, 0);

    assertThat(lower.maximumLifetimeMinutes()).isEqualTo(5);
    assertThat(lower.inactivityTimeoutMinutes()).isEqualTo(5);
    assertThat(lower.reverificationWindowMinutes()).isEqualTo(1);
    assertThat(upper.maximumLifetimeMinutes()).isEqualTo(5_256_000);
    assertThat(upper.inactivityTimeoutMinutes()).isEqualTo(525_600);
    assertThat(upper.reverificationWindowMinutes()).isEqualTo(10);
  }

  @Test
  void rejectsAMaximumLifetimeBelowFiveMinutes() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> SessionPolicy.define(organizationId, 4, 10, 5, true, 0));
  }

  @Test
  void rejectsAMaximumLifetimeAboveTenYears() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> SessionPolicy.define(organizationId, 5_256_001, 10, 5, true, 0));
  }

  @Test
  void rejectsAnInactivityTimeoutBelowFiveMinutes() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> SessionPolicy.define(organizationId, 10_080, 4, 5, true, 0));
  }

  @Test
  void rejectsAnInactivityTimeoutAboveOneYear() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> SessionPolicy.define(organizationId, 10_080, 525_601, 5, true, 0));
  }

  @Test
  void rejectsAReverificationWindowBelowOneMinute() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> SessionPolicy.define(organizationId, 10_080, 10_080, 0, true, 0));
  }

  @Test
  void rejectsAReverificationWindowAboveTenMinutes() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> SessionPolicy.define(organizationId, 10_080, 10_080, 11, true, 0));
  }

  // The original is rebuilt with a stored updatedAt a minute in the past, so the test proves the
  // new
  // stamp is later without waiting for the wall clock to move.
  @Test
  void withPolicyKeepsTheSameIdAndCreatedAtButStampsAFreshUpdatedAt() {
    SessionPolicy fresh = SessionPolicy.define(organizationId, 10_080, 10_080, 10, true, 0);
    SessionPolicy original =
        SessionPolicy.reconstitute(
            fresh.id(),
            fresh.organizationId(),
            fresh.maximumLifetimeMinutes(),
            fresh.inactivityTimeoutMinutes(),
            fresh.reverificationWindowMinutes(),
            fresh.multiSessionHandlingEnabled(),
            fresh.refreshTokenReuseGraceSeconds(),
            fresh.createdAt(),
            fresh.updatedAt().minusSeconds(60));

    SessionPolicy updated = original.withPolicy(20_160, 1_440, 5, false, 0);

    assertThat(updated.id()).isEqualTo(original.id());
    assertThat(updated.organizationId()).isEqualTo(original.organizationId());
    assertThat(updated.createdAt()).isEqualTo(original.createdAt());
    assertThat(updated.maximumLifetimeMinutes()).isEqualTo(20_160);
    assertThat(updated.inactivityTimeoutMinutes()).isEqualTo(1_440);
    assertThat(updated.reverificationWindowMinutes()).isEqualTo(5);
    assertThat(updated.multiSessionHandlingEnabled()).isFalse();
    assertThat(updated.updatedAt())
        .as("re-tuning an existing policy must stamp a real, later updatedAt")
        .isAfter(original.updatedAt());
  }

  @Test
  void withPolicyAlsoRejectsAnOutOfRangeValue() {
    SessionPolicy original = SessionPolicy.define(organizationId, 10_080, 10_080, 10, true, 0);

    assertThatIllegalArgumentException()
        .isThrownBy(() -> original.withPolicy(10_080, 10_080, 11, true, 0));
  }

  @Test
  void reconstituteKeepsTheRealPersistedIdRatherThanMintingANewOne() {
    UUID persistedId = UUID.randomUUID();
    Instant persistedCreatedAt = Instant.parse("2026-01-01T00:00:00Z");
    Instant persistedUpdatedAt = Instant.parse("2026-01-02T00:00:00Z");

    SessionPolicy policy =
        SessionPolicy.reconstitute(
            persistedId,
            organizationId,
            20_160,
            1_440,
            5,
            false,
            0,
            persistedCreatedAt,
            persistedUpdatedAt);

    assertThat(policy.id()).isEqualTo(persistedId);
    assertThat(policy.organizationId()).isEqualTo(organizationId);
    assertThat(policy.maximumLifetimeMinutes()).isEqualTo(20_160);
    assertThat(policy.inactivityTimeoutMinutes()).isEqualTo(1_440);
    assertThat(policy.reverificationWindowMinutes()).isEqualTo(5);
    assertThat(policy.multiSessionHandlingEnabled()).isFalse();
    assertThat(policy.createdAt()).isEqualTo(persistedCreatedAt);
    assertThat(policy.updatedAt()).isEqualTo(persistedUpdatedAt);
  }

  // TD-SEC-103: 0 is off, and it is what every Organization has until an operator chooses.
  @Test
  void theDefaultsLeaveTheRefreshTokenReuseWindowOff() {
    assertThat(SessionPolicy.defaults(organizationId).refreshTokenReuseGraceSeconds()).isZero();
  }

  @Test
  void theReuseGraceIsAcceptedFromZeroToSixtySecondsInclusive() {
    assertThat(
            SessionPolicy.define(organizationId, 10_080, 10_080, 10, true, 0)
                .refreshTokenReuseGraceSeconds())
        .isZero();
    assertThat(
            SessionPolicy.define(organizationId, 10_080, 10_080, 10, true, 60)
                .refreshTokenReuseGraceSeconds())
        .isEqualTo(60);
  }

  @Test
  void theReuseGraceRejectsANegativeValueAndAnythingAboveSixty() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> SessionPolicy.define(organizationId, 10_080, 10_080, 10, true, -1))
        .withMessageContaining("refreshTokenReuseGraceSeconds");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> SessionPolicy.define(organizationId, 10_080, 10_080, 10, true, 61))
        .withMessageContaining("seconds");
  }

  @Test
  void withPolicyChangesTheReuseGraceToo() {
    SessionPolicy original = SessionPolicy.define(organizationId, 10_080, 10_080, 10, true, 0);

    assertThat(original.withPolicy(10_080, 10_080, 10, true, 30).refreshTokenReuseGraceSeconds())
        .isEqualTo(30);
  }
}
