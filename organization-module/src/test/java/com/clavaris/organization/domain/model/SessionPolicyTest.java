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
    SessionPolicy policy = SessionPolicy.define(organizationId, 20_160, 1_440, 5, false);

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
    SessionPolicy lower = SessionPolicy.define(organizationId, 5, 5, 1, true);
    SessionPolicy upper = SessionPolicy.define(organizationId, 5_256_000, 525_600, 10, true);

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
        .isThrownBy(() -> SessionPolicy.define(organizationId, 4, 10, 5, true));
  }

  @Test
  void rejectsAMaximumLifetimeAboveTenYears() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> SessionPolicy.define(organizationId, 5_256_001, 10, 5, true));
  }

  @Test
  void rejectsAnInactivityTimeoutBelowFiveMinutes() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> SessionPolicy.define(organizationId, 10_080, 4, 5, true));
  }

  @Test
  void rejectsAnInactivityTimeoutAboveOneYear() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> SessionPolicy.define(organizationId, 10_080, 525_601, 5, true));
  }

  @Test
  void rejectsAReverificationWindowBelowOneMinute() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> SessionPolicy.define(organizationId, 10_080, 10_080, 0, true));
  }

  @Test
  void rejectsAReverificationWindowAboveTenMinutes() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> SessionPolicy.define(organizationId, 10_080, 10_080, 11, true));
  }

  // java:S2925: SessionPolicy has no injectable Clock (same Instant.now()-direct convention as
  // every other domain entity in this codebase) — same rationale RateLimitPolicyTest's own
  // identical suppression documents.
  @SuppressWarnings("java:S2925")
  @Test
  void withPolicyKeepsTheSameIdAndCreatedAtButStampsAFreshUpdatedAt() throws InterruptedException {
    SessionPolicy original = SessionPolicy.define(organizationId, 10_080, 10_080, 10, true);
    Thread.sleep(5);

    SessionPolicy updated = original.withPolicy(20_160, 1_440, 5, false);

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
    SessionPolicy original = SessionPolicy.define(organizationId, 10_080, 10_080, 10, true);

    assertThatIllegalArgumentException()
        .isThrownBy(() -> original.withPolicy(10_080, 10_080, 11, true));
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
}
