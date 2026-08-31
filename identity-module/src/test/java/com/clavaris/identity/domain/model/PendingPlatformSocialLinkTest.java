package com.clavaris.identity.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PendingPlatformSocialLinkTest {

  private final PlatformAccountId platformAccountId = new PlatformAccountId(UUID.randomUUID());

  @Test
  void raiseCarriesTheGivenFieldsAndStartsActiveAndUnconsumed() {
    Instant expiresAt = Instant.now().plus(1, ChronoUnit.HOURS);

    PendingPlatformSocialLink link =
        PendingPlatformSocialLink.raise(
            platformAccountId, SocialProvider.GOOGLE, "google-sub-123", "a-hash", expiresAt);

    assertThat(link.platformAccountId()).isEqualTo(platformAccountId);
    assertThat(link.provider()).isEqualTo(SocialProvider.GOOGLE);
    assertThat(link.providerUserId()).isEqualTo("google-sub-123");
    assertThat(link.confirmationTokenHash()).isEqualTo("a-hash");
    assertThat(link.expiresAt()).isEqualTo(expiresAt);
    assertThat(link.consumedAt()).isEmpty();
    assertThat(link.isActive()).isTrue();
  }

  @Test
  void consumeMarksTheLinkInactiveAndRecordsWhen() {
    PendingPlatformSocialLink link =
        PendingPlatformSocialLink.raise(
            platformAccountId,
            SocialProvider.GITHUB,
            "gh-456",
            "a-hash",
            Instant.now().plusSeconds(3600));

    link.consume();

    assertThat(link.consumedAt()).isPresent();
    assertThat(link.isActive()).isFalse();
  }

  @Test
  void aNaturallyExpiredUnconsumedLinkIsNotActive() {
    PendingPlatformSocialLink link =
        PendingPlatformSocialLink.raise(
            platformAccountId,
            SocialProvider.GOOGLE,
            "google-sub-123",
            "a-hash",
            Instant.now().minusSeconds(1));

    assertThat(link.consumedAt()).isEmpty();
    assertThat(link.isActive()).isFalse();
  }

  @Test
  void reconstituteRestoresEveryFieldExactly() {
    UUID id = UUID.randomUUID();
    Instant expiresAt = Instant.now().plusSeconds(3600);
    Instant consumedAt = Instant.now();

    PendingPlatformSocialLink link =
        PendingPlatformSocialLink.reconstitute(
            id,
            platformAccountId,
            SocialProvider.GITHUB,
            "gh-456",
            "a-hash",
            expiresAt,
            consumedAt);

    assertThat(link.id()).isEqualTo(id);
    assertThat(link.platformAccountId()).isEqualTo(platformAccountId);
    assertThat(link.provider()).isEqualTo(SocialProvider.GITHUB);
    assertThat(link.providerUserId()).isEqualTo("gh-456");
    assertThat(link.confirmationTokenHash()).isEqualTo("a-hash");
    assertThat(link.expiresAt()).isEqualTo(expiresAt);
    assertThat(link.consumedAt()).contains(consumedAt);
  }
}
