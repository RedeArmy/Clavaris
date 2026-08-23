package com.clavaris.organization.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class RateLimitPolicyTest {

  private final UUID organizationId = UUID.randomUUID();

  @Test
  void defineCarriesTheGivenOrganizationAndCeiling() {
    RateLimitPolicy policy = RateLimitPolicy.define(organizationId, 500, 6000);

    assertThat(policy.organizationId()).isEqualTo(organizationId);
    assertThat(policy.requestsPerMinute()).isEqualTo(500);
    assertThat(policy.id()).isNotNull();
    assertThat(policy.createdAt()).isNotNull();
    assertThat(policy.updatedAt()).isEqualTo(policy.createdAt());
  }

  @Test
  void rejectsACeilingAboveTheHardSystemWideCap() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> RateLimitPolicy.define(organizationId, 6001, 6000));
  }

  @Test
  void acceptsACeilingExactlyAtTheHardSystemWideCap() {
    RateLimitPolicy policy = RateLimitPolicy.define(organizationId, 6000, 6000);

    assertThat(policy.requestsPerMinute()).isEqualTo(6000);
  }

  @Test
  void rejectsANonPositiveCeiling() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> RateLimitPolicy.define(organizationId, 0, 6000));
  }

  // java:S2925: RateLimitPolicy has no injectable Clock (same Instant.now()-direct convention as
  // every other domain entity in this codebase) — a real wall-clock gap is the only way to prove
  // updatedAt genuinely advances rather than just that the field assignment compiles. 5ms is well
  // within Instant's real resolution on any JVM this project targets.
  @SuppressWarnings("java:S2925")
  @Test
  void withRequestsPerMinuteKeepsTheSameIdAndCreatedAtButStampsAFreshUpdatedAt()
      throws InterruptedException {
    RateLimitPolicy original = RateLimitPolicy.define(organizationId, 500, 6000);
    Thread.sleep(5);

    RateLimitPolicy updated = original.withRequestsPerMinute(800, 6000);

    assertThat(updated.id()).isEqualTo(original.id());
    assertThat(updated.organizationId()).isEqualTo(original.organizationId());
    assertThat(updated.createdAt()).isEqualTo(original.createdAt());
    assertThat(updated.requestsPerMinute()).isEqualTo(800);
    assertThat(updated.updatedAt())
        .as("re-tuning an existing ceiling must stamp a real, later updatedAt")
        .isAfter(original.updatedAt());
  }

  @Test
  void withRequestsPerMinuteAlsoRejectsACeilingAboveTheHardSystemWideCap() {
    RateLimitPolicy original = RateLimitPolicy.define(organizationId, 500, 6000);

    assertThatIllegalArgumentException()
        .isThrownBy(() -> original.withRequestsPerMinute(6001, 6000));
  }

  @Test
  void reconstituteKeepsTheRealPersistedIdRatherThanMintingANewOne() {
    // Same bug class already caught once in Organization's own history — reconstitute must
    // return the exact id passed in, not a fresh UUID.randomUUID().
    UUID persistedId = UUID.randomUUID();
    java.time.Instant persistedCreatedAt = java.time.Instant.parse("2026-01-01T00:00:00Z");
    java.time.Instant persistedUpdatedAt = java.time.Instant.parse("2026-01-02T00:00:00Z");

    RateLimitPolicy policy =
        RateLimitPolicy.reconstitute(
            persistedId, organizationId, 500, persistedCreatedAt, persistedUpdatedAt);

    assertThat(policy.id()).isEqualTo(persistedId);
    assertThat(policy.organizationId()).isEqualTo(organizationId);
    assertThat(policy.requestsPerMinute()).isEqualTo(500);
    assertThat(policy.createdAt()).isEqualTo(persistedCreatedAt);
    assertThat(policy.updatedAt()).isEqualTo(persistedUpdatedAt);
  }
}
