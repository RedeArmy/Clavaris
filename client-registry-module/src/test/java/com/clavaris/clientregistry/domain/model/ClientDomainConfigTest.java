package com.clavaris.clientregistry.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.Collections;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClientDomainConfigTest {

  private final UUID oauthClientId = UUID.randomUUID();

  @Test
  void requestStartsPendingWithAFreshChallengeToken() {
    ClientDomainConfig config =
        ClientDomainConfig.request(
            oauthClientId, ClientDomainMode.CNAME, "login.example.com", null);

    assertThat(config.oauthClientId()).isEqualTo(oauthClientId);
    assertThat(config.mode()).contains(ClientDomainMode.CNAME);
    assertThat(config.hostname()).contains("login.example.com");
    assertThat(config.verificationStatus()).contains(DomainVerificationStatus.PENDING);
    assertThat(config.dnsTxtChallengeToken()).isPresent();
    assertThat(config.verifiedAt()).isEmpty();
    assertThat(config.isVerified()).isFalse();
  }

  @Test
  void unconfiguredHasEveryDomainSpecificFieldAbsent() {
    ClientDomainConfig config = ClientDomainConfig.unconfigured(oauthClientId);

    assertThat(config.mode()).isEmpty();
    assertThat(config.hostname()).isEmpty();
    assertThat(config.verificationStatus()).isEmpty();
    assertThat(config.dnsTxtChallengeToken()).isEmpty();
    assertThat(config.isVerified()).isFalse();
  }

  @Test
  void rejectsABlankHostname() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> ClientDomainConfig.request(oauthClientId, ClientDomainMode.CNAME, "   ", null));
  }

  @Test
  void rejectsAHostnameWithAScheme() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                ClientDomainConfig.request(
                    oauthClientId, ClientDomainMode.CNAME, "https://login.example.com", null));
  }

  @Test
  void rejectsASingleLabelHostname() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                ClientDomainConfig.request(
                    oauthClientId, ClientDomainMode.CNAME, "localhost", null));
  }

  @Test
  void rejectsALabelStartingWithAHyphen() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                ClientDomainConfig.request(
                    oauthClientId, ClientDomainMode.CNAME, "-login.example.com", null));
  }

  @Test
  void rejectsALabelEndingWithAHyphen() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                ClientDomainConfig.request(
                    oauthClientId, ClientDomainMode.CNAME, "login-.example.com", null));
  }

  @Test
  void acceptsAHyphenInTheMiddleOfALabel() {
    ClientDomainConfig config =
        ClientDomainConfig.request(
            oauthClientId, ClientDomainMode.CNAME, "sign-in.example.com", null);

    assertThat(config.hostname()).contains("sign-in.example.com");
  }

  @Test
  void rejectsASingleLabelLongerThanRfc1035Allows() {
    // Each label caps at 63 characters — isValidLabel's own MAX_LABEL_LENGTH ceiling, exercised
    // directly rather than via the whole-hostname length guard below.
    final String oversizedLabel = "a".repeat(64) + ".example.com";

    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                ClientDomainConfig.request(
                    oauthClientId, ClientDomainMode.CNAME, oversizedLabel, null));
  }

  @Test
  void rejectsAHostnameLongerThanRfc1035AllowsOverall() {
    // Every individual label here ("ab") is well within isValidLabel's own 63-character limit —
    // this exercises isValidHostname's separate, whole-hostname MAX_HOSTNAME_LENGTH guard, not
    // the per-label one above.
    final String oversizedHostname = String.join(".", Collections.nCopies(130, "ab"));

    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                ClientDomainConfig.request(
                    oauthClientId, ClientDomainMode.CNAME, oversizedHostname, null));
  }

  @Test
  void reRequestKeepsTheSameIdButMintsAFreshTokenAndResetsToPending() {
    ClientDomainConfig original =
        ClientDomainConfig.request(
            oauthClientId, ClientDomainMode.CNAME, "login.example.com", null);
    ClientDomainConfig verified = original.markVerified();

    ClientDomainConfig reRequested =
        verified.reRequest(ClientDomainMode.PROXY, "sso.example.com", null);

    assertThat(reRequested.id()).isEqualTo(original.id());
    assertThat(reRequested.mode()).contains(ClientDomainMode.PROXY);
    assertThat(reRequested.hostname()).contains("sso.example.com");
    assertThat(reRequested.verificationStatus()).contains(DomainVerificationStatus.PENDING);
    assertThat(reRequested.verifiedAt())
        .as("a changed hostname/mode must re-prove ownership, never inherit a prior verification")
        .isEmpty();
    assertThat(reRequested.dnsTxtChallengeToken())
        .as("re-requesting must mint a fresh token, not reuse the old one")
        .isNotEqualTo(original.dnsTxtChallengeToken());
  }

  @Test
  void markVerifiedSetsVerifiedAtAndMakesTheConfigEmbeddingEligible() {
    ClientDomainConfig pending =
        ClientDomainConfig.request(
            oauthClientId, ClientDomainMode.CNAME, "login.example.com", null);

    ClientDomainConfig verified = pending.markVerified();

    assertThat(verified.verificationStatus()).contains(DomainVerificationStatus.VERIFIED);
    assertThat(verified.verifiedAt()).isPresent();
    assertThat(verified.isVerified()).isTrue();
  }

  @Test
  void markFailedKeepsTheSameHostnameAndTokenForARetry() {
    ClientDomainConfig pending =
        ClientDomainConfig.request(
            oauthClientId, ClientDomainMode.CNAME, "login.example.com", null);

    ClientDomainConfig failed = pending.markFailed();

    assertThat(failed.verificationStatus()).contains(DomainVerificationStatus.FAILED);
    assertThat(failed.hostname()).isEqualTo(pending.hostname());
    assertThat(failed.dnsTxtChallengeToken()).isEqualTo(pending.dnsTxtChallengeToken());
    assertThat(failed.isVerified()).isFalse();
  }

  @Test
  void reconstituteKeepsTheRealPersistedIdRatherThanMintingANewOne() {
    UUID persistedId = UUID.randomUUID();
    java.time.Instant persistedCreatedAt = java.time.Instant.parse("2026-01-01T00:00:00Z");
    java.time.Instant persistedUpdatedAt = java.time.Instant.parse("2026-01-02T00:00:00Z");

    ClientDomainConfig config =
        ClientDomainConfig.reconstitute(
            persistedId,
            oauthClientId,
            ClientDomainMode.CNAME,
            "login.example.com",
            DomainVerificationStatus.VERIFIED,
            "some-token",
            "https://app.example.com",
            persistedUpdatedAt,
            persistedCreatedAt,
            persistedUpdatedAt);

    assertThat(config.id()).isEqualTo(persistedId);
    assertThat(config.oauthClientId()).isEqualTo(oauthClientId);
    assertThat(config.createdAt()).isEqualTo(persistedCreatedAt);
    assertThat(config.updatedAt()).isEqualTo(persistedUpdatedAt);
  }
}
