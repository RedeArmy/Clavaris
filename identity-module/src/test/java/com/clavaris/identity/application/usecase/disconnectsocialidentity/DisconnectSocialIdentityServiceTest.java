package com.clavaris.identity.application.usecase.disconnectsocialidentity;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.authenticatewithsocialprovider.SocialIdentityRepository;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.registerwebauthncredential.WebAuthnCredentialRepository;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.SocialIdentity;
import com.clavaris.identity.domain.model.SocialProvider;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DisconnectSocialIdentityServiceTest {

  private final OrganizationId organizationId = new OrganizationId(UUID.randomUUID());

  private AccountRepository accounts;
  private SocialIdentityRepository identities;
  private WebAuthnCredentialRepository passkeys;
  private DisconnectSocialIdentityService service;
  private Account account;

  @BeforeEach
  void setUp() {
    accounts = mock(AccountRepository.class);
    identities = mock(SocialIdentityRepository.class);
    passkeys = mock(WebAuthnCredentialRepository.class);
    service =
        new DisconnectSocialIdentityService(
            accounts, identities, passkeys, mock(AuditEventRecorder.class));
    account = Account.register(organizationId, new Email("holder@example.com"));
    when(accounts.findById(account.id())).thenReturn(Optional.of(account));
    when(passkeys.findAllByAccountId(account.id())).thenReturn(List.of());
  }

  private SocialIdentity link(final SocialProvider provider) {
    return SocialIdentity.link(account.id(), organizationId, provider, "sub-" + provider);
  }

  private DisconnectSocialIdentityCommand command(final SocialProvider provider) {
    return new DisconnectSocialIdentityCommand(
        account.id(), provider, AuditActor.account(account.id().value()));
  }

  @Test
  void removesTheLinkWhenAPasswordRemains() {
    account.attachPasswordCredential("argon2id$hash");
    when(identities.findAllByAccountId(account.id()))
        .thenReturn(List.of(link(SocialProvider.GITHUB)));

    service.handle(command(SocialProvider.GITHUB));

    verify(identities).deleteByAccountIdAndProvider(account.id(), SocialProvider.GITHUB);
  }

  @Test
  void removesTheLinkWhenAnotherProviderRemains() {
    when(identities.findAllByAccountId(account.id()))
        .thenReturn(List.of(link(SocialProvider.GITHUB), link(SocialProvider.GOOGLE)));

    service.handle(command(SocialProvider.GITHUB));

    verify(identities).deleteByAccountIdAndProvider(account.id(), SocialProvider.GITHUB);
  }

  @Test
  void refusesToRemoveTheOnlyWayToSignIn() {
    when(identities.findAllByAccountId(account.id()))
        .thenReturn(List.of(link(SocialProvider.GITHUB)));

    final DisconnectSocialIdentityCommand attempt = command(SocialProvider.GITHUB);
    assertThatExceptionOfType(LastSignInMethodException.class)
        .isThrownBy(() -> service.handle(attempt));
    verify(identities, never()).deleteByAccountIdAndProvider(any(), any());
  }

  @Test
  void aProviderThatIsNotLinkedIsReported() {
    account.attachPasswordCredential("argon2id$hash");
    when(identities.findAllByAccountId(account.id())).thenReturn(List.of());

    final DisconnectSocialIdentityCommand attempt = command(SocialProvider.GOOGLE);
    assertThatExceptionOfType(SocialIdentityNotLinkedException.class)
        .isThrownBy(() -> service.handle(attempt));
  }
}
