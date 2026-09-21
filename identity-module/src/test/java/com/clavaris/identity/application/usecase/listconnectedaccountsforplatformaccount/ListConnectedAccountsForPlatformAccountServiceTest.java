package com.clavaris.identity.application.usecase.listconnectedaccountsforplatformaccount;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clavaris.identity.application.usecase.authenticateplatformaccountwithsocialprovider.PlatformSocialIdentityRepository;
import com.clavaris.identity.domain.model.PlatformAccountId;
import com.clavaris.identity.domain.model.PlatformSocialIdentity;
import com.clavaris.identity.domain.model.SocialProvider;
import java.util.List;
import org.junit.jupiter.api.Test;

class ListConnectedAccountsForPlatformAccountServiceTest {

  @Test
  void mapsEachLinkedSocialIdentityToAConnectedAccount() {
    PlatformSocialIdentityRepository identities = mock(PlatformSocialIdentityRepository.class);
    PlatformAccountId platformAccountId = PlatformAccountId.newId();
    PlatformSocialIdentity identity =
        PlatformSocialIdentity.link(platformAccountId, SocialProvider.GOOGLE, "google-sub-123");
    when(identities.findAllByPlatformAccountId(platformAccountId)).thenReturn(List.of(identity));
    ListConnectedAccountsForPlatformAccountService service =
        new ListConnectedAccountsForPlatformAccountService(identities);

    List<ConnectedAccount> result = service.handle(platformAccountId);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).provider()).isEqualTo(SocialProvider.GOOGLE);
    assertThat(result.get(0).linkedAt()).isEqualTo(identity.linkedAt());
  }

  @Test
  void returnsAnEmptyListWhenNothingIsLinked() {
    PlatformSocialIdentityRepository identities = mock(PlatformSocialIdentityRepository.class);
    PlatformAccountId platformAccountId = PlatformAccountId.newId();
    when(identities.findAllByPlatformAccountId(platformAccountId)).thenReturn(List.of());
    ListConnectedAccountsForPlatformAccountService service =
        new ListConnectedAccountsForPlatformAccountService(identities);

    assertThat(service.handle(platformAccountId)).isEmpty();
  }
}
