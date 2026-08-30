package com.clavaris.identity.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SocialIdentityTest {

  private final AccountId accountId = new AccountId(UUID.randomUUID());
  private final OrganizationId organizationId = new OrganizationId(UUID.randomUUID());

  @Test
  void linkCarriesTheGivenFieldsAndStampsLinkedAtNow() {
    SocialIdentity identity =
        SocialIdentity.link(accountId, organizationId, SocialProvider.GOOGLE, "google-sub-123");

    assertThat(identity.accountId()).isEqualTo(accountId);
    assertThat(identity.organizationId()).isEqualTo(organizationId);
    assertThat(identity.provider()).isEqualTo(SocialProvider.GOOGLE);
    assertThat(identity.providerUserId()).isEqualTo("google-sub-123");
    assertThat(identity.linkedAt()).isNotNull();
  }

  @Test
  void rejectsABlankProviderUserId() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(
            () -> SocialIdentity.link(accountId, organizationId, SocialProvider.GITHUB, "  "));
  }

  @Test
  void reconstituteRestoresEveryFieldExactly() {
    UUID id = UUID.randomUUID();
    Instant linkedAt = Instant.now().minusSeconds(3600);

    SocialIdentity identity =
        SocialIdentity.reconstitute(
            id, accountId, organizationId, SocialProvider.GITHUB, "gh-456", linkedAt);

    assertThat(identity.id()).isEqualTo(id);
    assertThat(identity.accountId()).isEqualTo(accountId);
    assertThat(identity.organizationId()).isEqualTo(organizationId);
    assertThat(identity.provider()).isEqualTo(SocialProvider.GITHUB);
    assertThat(identity.providerUserId()).isEqualTo("gh-456");
    assertThat(identity.linkedAt()).isEqualTo(linkedAt);
  }
}
