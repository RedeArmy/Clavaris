package com.clavaris.identity.application.usecase.getplatformaccountavatar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clavaris.identity.application.usecase.registerplatformaccount.PlatformAccountRepository;
import com.clavaris.identity.application.usecase.updateaccountprofilepicture.ProfilePictureStorage;
import com.clavaris.identity.application.usecase.updateaccountprofilepicture.StoredProfilePicture;
import com.clavaris.identity.domain.model.AccountStatus;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.PlatformAccount;
import com.clavaris.identity.domain.model.PlatformAccountId;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GetPlatformAccountAvatarServiceTest {

  private PlatformAccountRepository accounts;
  private ProfilePictureStorage storage;
  private GetPlatformAccountAvatarService service;
  private PlatformAccount account;

  @BeforeEach
  void setUp() {
    accounts = mock(PlatformAccountRepository.class);
    storage = mock(ProfilePictureStorage.class);
    service = new GetPlatformAccountAvatarService(accounts, storage);
    account =
        PlatformAccount.reconstitute(
            PlatformAccountId.newId(),
            new Email("ada@example.com"),
            Instant.now(),
            null,
            AccountStatus.ACTIVE,
            null,
            "Ada",
            "Lovelace",
            null);
    when(accounts.findById(account.id())).thenReturn(Optional.of(account));
  }

  @Test
  void returnsEmptyForAnUnknownAccount() {
    when(accounts.findById(any())).thenReturn(Optional.empty());

    assertThat(service.handle(PlatformAccountId.newId())).isEmpty();
  }

  @Test
  void redirectsToAnExternalSocialProviderUrl() {
    account.updateProfilePicture("https://avatars.githubusercontent.com/u/1");

    PlatformAccountAvatarResult result = service.handle(account.id()).orElseThrow();

    assertThat(result).isInstanceOf(PlatformAccountAvatarResult.Redirect.class);
    assertThat(((PlatformAccountAvatarResult.Redirect) result).externalUrl())
        .isEqualTo("https://avatars.githubusercontent.com/u/1");
  }

  @Test
  void servesStoredContentForAClavarisManagedUpload() {
    account.updateProfilePicture("platform-avatars/" + account.id().value());
    when(storage.download("platform-avatars/" + account.id().value()))
        .thenReturn(new StoredProfilePicture(new byte[] {1, 2, 3}, "image/png"));

    PlatformAccountAvatarResult result = service.handle(account.id()).orElseThrow();

    assertThat(result).isInstanceOf(PlatformAccountAvatarResult.Content.class);
    PlatformAccountAvatarResult.Content content = (PlatformAccountAvatarResult.Content) result;
    assertThat(content.contentType()).isEqualTo("image/png");
  }

  @Test
  void generatesAnInitialsSvgWhenNoPictureIsSet() {
    PlatformAccountAvatarResult result = service.handle(account.id()).orElseThrow();

    assertThat(result).isInstanceOf(PlatformAccountAvatarResult.Content.class);
    PlatformAccountAvatarResult.Content content = (PlatformAccountAvatarResult.Content) result;
    assertThat(content.contentType()).isEqualTo("image/svg+xml");
    String svg = new String(content.content(), StandardCharsets.UTF_8);
    assertThat(svg).contains("<svg").contains("AL");
  }
}
