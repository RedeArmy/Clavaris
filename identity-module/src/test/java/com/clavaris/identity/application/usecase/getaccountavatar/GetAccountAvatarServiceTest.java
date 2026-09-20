package com.clavaris.identity.application.usecase.getaccountavatar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.updateaccountprofilepicture.ProfilePictureStorage;
import com.clavaris.identity.application.usecase.updateaccountprofilepicture.StoredProfilePicture;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.AccountStatus;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GetAccountAvatarServiceTest {

  private AccountRepository accounts;
  private ProfilePictureStorage storage;
  private GetAccountAvatarService service;
  private OrganizationId organizationId;
  private Account account;

  @BeforeEach
  void setUp() {
    accounts = mock(AccountRepository.class);
    storage = mock(ProfilePictureStorage.class);
    service = new GetAccountAvatarService(accounts, storage);
    organizationId = new OrganizationId(UUID.randomUUID());
    account =
        Account.reconstitute(
            AccountId.newId(),
            organizationId,
            new Email("ada@example.com"),
            Instant.now(),
            null,
            AccountStatus.ACTIVE,
            null,
            null,
            null,
            "Ada",
            "Lovelace",
            null,
            null,
            null);
    when(accounts.findById(account.id())).thenReturn(Optional.of(account));
  }

  private GetAccountAvatarQuery query() {
    return new GetAccountAvatarQuery(organizationId, account.id());
  }

  @Test
  void returnsEmptyForAnUnknownAccount() {
    when(accounts.findById(any())).thenReturn(Optional.empty());

    assertThat(service.handle(query())).isEmpty();
  }

  @Test
  void returnsEmptyWhenTheAccountBelongsToADifferentOrganization_neverDistinguishableFromUnknown() {
    GetAccountAvatarQuery mismatched =
        new GetAccountAvatarQuery(new OrganizationId(UUID.randomUUID()), account.id());

    assertThat(service.handle(mismatched)).isEmpty();
  }

  @Test
  void redirectsToAnExternalSocialProviderUrl() {
    account.updateProfilePicture("https://lh3.googleusercontent.com/a/avatar.png");

    AccountAvatarResult result = service.handle(query()).orElseThrow();

    assertThat(result).isInstanceOf(AccountAvatarResult.Redirect.class);
    assertThat(((AccountAvatarResult.Redirect) result).externalUrl())
        .isEqualTo("https://lh3.googleusercontent.com/a/avatar.png");
  }

  @Test
  void servesStoredContentForAClavarisManagedUpload() {
    account.updateProfilePicture("avatars/" + account.id());
    when(storage.download("avatars/" + account.id()))
        .thenReturn(new StoredProfilePicture(new byte[] {1, 2, 3}, "image/png"));

    AccountAvatarResult result = service.handle(query()).orElseThrow();

    assertThat(result).isInstanceOf(AccountAvatarResult.Content.class);
    AccountAvatarResult.Content content = (AccountAvatarResult.Content) result;
    assertThat(content.contentType()).isEqualTo("image/png");
    assertThat(content.content()).containsExactly(1, 2, 3);
  }

  @Test
  void generatesAnInitialsSvgWhenNoPictureIsSet() {
    AccountAvatarResult result = service.handle(query()).orElseThrow();

    assertThat(result).isInstanceOf(AccountAvatarResult.Content.class);
    AccountAvatarResult.Content content = (AccountAvatarResult.Content) result;
    assertThat(content.contentType()).isEqualTo("image/svg+xml");
    String svg = new String(content.content(), StandardCharsets.UTF_8);
    assertThat(svg).contains("<svg").contains("AL");
  }

  @Test
  void fallsBackToTheEmailWhenNoNameIsSet() {
    Account noName =
        Account.reconstitute(
            AccountId.newId(),
            organizationId,
            new Email("zed@example.com"),
            Instant.now(),
            null,
            AccountStatus.ACTIVE,
            null,
            null,
            null);
    when(accounts.findById(noName.id())).thenReturn(Optional.of(noName));

    AccountAvatarResult.Content content =
        (AccountAvatarResult.Content)
            service.handle(new GetAccountAvatarQuery(organizationId, noName.id())).orElseThrow();

    String svg = new String(content.content(), StandardCharsets.UTF_8);
    assertThat(svg).contains(">Z<");
  }
}
