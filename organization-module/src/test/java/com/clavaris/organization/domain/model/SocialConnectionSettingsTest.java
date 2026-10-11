package com.clavaris.organization.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class SocialConnectionSettingsTest {

  private final UUID organizationId = UUID.randomUUID();

  // No saved row has to mean how social login behaved before these settings existed.
  @Test
  void unsetBlocksNothingAndForcesNoChooser() {
    final SocialConnectionSettings unset =
        SocialConnectionSettings.unset(organizationId, SocialProvider.GOOGLE);

    assertThat(unset.blockEmailSubaddresses()).isFalse();
    assertThat(unset.alwaysShowAccountSelector()).isFalse();
  }

  @Test
  void changingTheValuesKeepsTheRowsIdentityAndCreationTime() {
    final SocialConnectionSettings original =
        SocialConnectionSettings.define(organizationId, SocialProvider.GITHUB, false, false);

    final SocialConnectionSettings changed = original.with(true, true);

    assertThat(changed.id()).isEqualTo(original.id());
    assertThat(changed.createdAt()).isEqualTo(original.createdAt());
    assertThat(changed.organizationId()).isEqualTo(organizationId);
    assertThat(changed.provider()).isEqualTo(SocialProvider.GITHUB);
    assertThat(changed.blockEmailSubaddresses()).isTrue();
    assertThat(changed.alwaysShowAccountSelector()).isTrue();
    assertThat(changed.updatedAt()).isAfterOrEqualTo(original.updatedAt());
  }
}
