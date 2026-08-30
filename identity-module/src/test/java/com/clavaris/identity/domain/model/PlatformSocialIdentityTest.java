package com.clavaris.identity.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlatformSocialIdentityTest {

  private final PlatformAccountId platformAccountId = new PlatformAccountId(UUID.randomUUID());

  @Test
  void linkCarriesTheGivenFieldsAndStampsLinkedAtNow() {
    PlatformSocialIdentity identity =
        PlatformSocialIdentity.link(platformAccountId, SocialProvider.GOOGLE, "google-sub-123");

    assertThat(identity.platformAccountId()).isEqualTo(platformAccountId);
    assertThat(identity.provider()).isEqualTo(SocialProvider.GOOGLE);
    assertThat(identity.providerUserId()).isEqualTo("google-sub-123");
    assertThat(identity.linkedAt()).isNotNull();
  }

  @Test
  void rejectsABlankProviderUserId() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(
            () -> PlatformSocialIdentity.link(platformAccountId, SocialProvider.GITHUB, ""));
  }

  @Test
  void reconstituteRestoresEveryFieldExactly() {
    UUID id = UUID.randomUUID();
    Instant linkedAt = Instant.now().minusSeconds(3600);

    PlatformSocialIdentity identity =
        PlatformSocialIdentity.reconstitute(
            id, platformAccountId, SocialProvider.GITHUB, "gh-456", linkedAt);

    assertThat(identity.id()).isEqualTo(id);
    assertThat(identity.platformAccountId()).isEqualTo(platformAccountId);
    assertThat(identity.provider()).isEqualTo(SocialProvider.GITHUB);
    assertThat(identity.providerUserId()).isEqualTo("gh-456");
    assertThat(identity.linkedAt()).isEqualTo(linkedAt);
  }
}
