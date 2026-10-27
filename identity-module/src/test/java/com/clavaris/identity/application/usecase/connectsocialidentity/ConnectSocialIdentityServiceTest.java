package com.clavaris.identity.application.usecase.connectsocialidentity;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.identity.application.usecase.authenticatewithsocialprovider.OrganizationSocialLoginPolicyProvider;
import com.clavaris.identity.application.usecase.authenticatewithsocialprovider.SocialIdentityRepository;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
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

class ConnectSocialIdentityServiceTest {

  private final OrganizationId organizationId = new OrganizationId(UUID.randomUUID());

  private SocialIdentityRepository identities;
  private OrganizationSocialLoginPolicyProvider policy;
  private ConnectSocialIdentityService service;
  private Account account;

  @BeforeEach
  void setUp() {
    final AccountRepository accounts = mock(AccountRepository.class);
    identities = mock(SocialIdentityRepository.class);
    policy = mock(OrganizationSocialLoginPolicyProvider.class);
    service =
        new ConnectSocialIdentityService(
            accounts, identities, policy, mock(AuditEventRecorder.class));
    account = Account.register(organizationId, new Email("holder@example.com"));
    when(accounts.findById(account.id())).thenReturn(Optional.of(account));
    when(policy.isProviderAllowed(organizationId, SocialProvider.GITHUB)).thenReturn(true);
    when(identities.findAllByAccountId(account.id())).thenReturn(List.of());
    when(identities.findByOrganizationIdAndProviderAndProviderUserId(any(), any(), any()))
        .thenReturn(Optional.empty());
  }

  private ConnectSocialIdentityCommand command() {
    return new ConnectSocialIdentityCommand(account.id(), SocialProvider.GITHUB, "gh-42");
  }

  @Test
  void linksTheProviderIdentityToTheAccount() {
    service.handle(command());

    verify(identities).save(any(SocialIdentity.class));
  }

  @Test
  void aProviderTheOrganizationHasNotEnabledIsRefused() {
    when(policy.isProviderAllowed(organizationId, SocialProvider.GITHUB)).thenReturn(false);

    assertThatExceptionOfType(SocialConnectionNotAllowedException.class)
        .isThrownBy(() -> service.handle(command()));
    verify(identities, never()).save(any());
  }

  @Test
  void aProviderIdentityBelongingToAnotherAccountIsRefused() {
    when(identities.findByOrganizationIdAndProviderAndProviderUserId(
            organizationId, SocialProvider.GITHUB, "gh-42"))
        .thenReturn(
            Optional.of(
                SocialIdentity.link(
                    com.clavaris.identity.domain.model.AccountId.newId(),
                    organizationId,
                    SocialProvider.GITHUB,
                    "gh-42")));

    assertThatExceptionOfType(SocialIdentityAlreadyLinkedException.class)
        .isThrownBy(() -> service.handle(command()));
    verify(identities, never()).save(any());
  }

  @Test
  void theSameProviderCannotBeLinkedTwice() {
    when(identities.findAllByAccountId(account.id()))
        .thenReturn(
            List.of(
                SocialIdentity.link(account.id(), organizationId, SocialProvider.GITHUB, "other")));

    assertThatExceptionOfType(SocialIdentityAlreadyLinkedException.class)
        .isThrownBy(() -> service.handle(command()));
  }
}
