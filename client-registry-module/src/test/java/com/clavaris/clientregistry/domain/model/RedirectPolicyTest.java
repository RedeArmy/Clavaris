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

  // The original is rebuilt with a stored updatedAt a minute in the past, so the test proves the
  // new
  // stamp is later without waiting for the wall clock to move.
  @Test
  void withUrlsKeepsTheSameIdAndCreatedAtButStampsAFreshUpdatedAt() {
    RedirectPolicy fresh =
        RedirectPolicy.define(oauthClientId, "https://app.example.com/a", null, null, null);
    RedirectPolicy original =
        RedirectPolicy.reconstitute(
            fresh.id(),
            fresh.oauthClientId(),
            "https://app.example.com/a",
            null,
            null,
            null,
            fresh.createdAt(),
            fresh.updatedAt().minusSeconds(60));

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
