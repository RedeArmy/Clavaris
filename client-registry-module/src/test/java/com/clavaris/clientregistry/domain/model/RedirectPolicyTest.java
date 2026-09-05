package com.clavaris.clientregistry.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RedirectPolicyTest {

  private final UUID oauthClientId = UUID.randomUUID();

  @Test
  void defineCarriesTheGivenClientAndUrls() {
    RedirectPolicy policy =
        RedirectPolicy.define(
            oauthClientId,
            "https://app.example.com/after-sign-in",
            "https://app.example.com/after-sign-up",
            null,
            null);

    assertThat(policy.oauthClientId()).isEqualTo(oauthClientId);
    assertThat(policy.fallbackSignInRedirectUrl())
        .contains("https://app.example.com/after-sign-in");
    assertThat(policy.fallbackSignUpRedirectUrl())
        .contains("https://app.example.com/after-sign-up");
    assertThat(policy.forceSignInRedirectUrl()).isEmpty();
    assertThat(policy.forceSignUpRedirectUrl()).isEmpty();
    assertThat(policy.id()).isNotNull();
    assertThat(policy.updatedAt()).isEqualTo(policy.createdAt());
  }

  @Test
  void unconfiguredHasEveryUrlAbsent() {
    RedirectPolicy policy = RedirectPolicy.unconfigured(oauthClientId);

    assertThat(policy.fallbackSignInRedirectUrl()).isEmpty();
    assertThat(policy.fallbackSignUpRedirectUrl()).isEmpty();
    assertThat(policy.forceSignInRedirectUrl()).isEmpty();
    assertThat(policy.forceSignUpRedirectUrl()).isEmpty();
  }

  @Test
  void rejectsAMalformedUrl() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> RedirectPolicy.define(oauthClientId, "not a url", null, null, null));
  }

  @Test
  void rejectsARelativeUrl() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> RedirectPolicy.define(oauthClientId, "/after-sign-in", null, null, null));
  }

  @Test
  void rejectsAnInsecureHttpUrlAgainstANonLoopbackHost() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                RedirectPolicy.define(
                    oauthClientId, "http://app.example.com/after-sign-in", null, null, null));
  }

  @Test
  void acceptsHttpAgainstLocalhostForDevTesting() {
    RedirectPolicy policy =
        RedirectPolicy.define(
            oauthClientId, "http://localhost:3000/after-sign-in", null, null, null);

    assertThat(policy.fallbackSignInRedirectUrl()).contains("http://localhost:3000/after-sign-in");
  }

  // java:S2925: RedirectPolicy has no injectable Clock, same Instant.now()-direct convention as
  // every other domain entity in this codebase — see RateLimitPolicyTest's own identical
  // suppression rationale.
  @SuppressWarnings("java:S2925")
  @Test
  void withUrlsKeepsTheSameIdAndCreatedAtButStampsAFreshUpdatedAt() throws InterruptedException {
    RedirectPolicy original =
        RedirectPolicy.define(oauthClientId, "https://app.example.com/a", null, null, null);
    Thread.sleep(5);

    RedirectPolicy updated = original.withUrls("https://app.example.com/b", null, null, null);

    assertThat(updated.id()).isEqualTo(original.id());
    assertThat(updated.oauthClientId()).isEqualTo(original.oauthClientId());
    assertThat(updated.createdAt()).isEqualTo(original.createdAt());
    assertThat(updated.fallbackSignInRedirectUrl()).contains("https://app.example.com/b");
    assertThat(updated.updatedAt())
        .as("re-tuning an existing policy must stamp a real, later updatedAt")
        .isAfter(original.updatedAt());
  }

  @Test
  void reconstituteKeepsTheRealPersistedIdRatherThanMintingANewOne() {
    UUID persistedId = UUID.randomUUID();
    Instant persistedCreatedAt = Instant.parse("2026-01-01T00:00:00Z");
    Instant persistedUpdatedAt = Instant.parse("2026-01-02T00:00:00Z");

    RedirectPolicy policy =
        RedirectPolicy.reconstitute(
            persistedId,
            oauthClientId,
            "https://app.example.com/a",
            null,
            null,
            null,
            persistedCreatedAt,
            persistedUpdatedAt);

    assertThat(policy.id()).isEqualTo(persistedId);
    assertThat(policy.oauthClientId()).isEqualTo(oauthClientId);
    assertThat(policy.createdAt()).isEqualTo(persistedCreatedAt);
    assertThat(policy.updatedAt()).isEqualTo(persistedUpdatedAt);
  }
}
