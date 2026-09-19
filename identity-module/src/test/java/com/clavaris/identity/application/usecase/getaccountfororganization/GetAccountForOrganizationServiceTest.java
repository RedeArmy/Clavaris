package com.clavaris.identity.application.usecase.getaccountfororganization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GetAccountForOrganizationServiceTest {

  @Test
  void returnsTheAccountWhenItBelongsToTheGivenOrganization() {
    OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
    Account account = Account.register(organizationId, new Email("ada@example.com"));
    AccountRepository accounts = mock(AccountRepository.class);
    when(accounts.findById(account.id())).thenReturn(Optional.of(account));
    GetAccountForOrganizationService service = new GetAccountForOrganizationService(accounts);

    Optional<Account> result =
        service.handle(new GetAccountForOrganizationQuery(organizationId, account.id()));

    assertThat(result).contains(account);
  }

  @Test
  void isEmptyWhenTheAccountBelongsToADifferentOrganization() {
    OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
    OrganizationId otherOrganizationId = new OrganizationId(UUID.randomUUID());
    Account account = Account.register(otherOrganizationId, new Email("ada@example.com"));
    AccountRepository accounts = mock(AccountRepository.class);
    when(accounts.findById(account.id())).thenReturn(Optional.of(account));
    GetAccountForOrganizationService service = new GetAccountForOrganizationService(accounts);

    Optional<Account> result =
        service.handle(new GetAccountForOrganizationQuery(organizationId, account.id()));

    assertThat(result).isEmpty();
  }

  @Test
  void isEmptyWhenNoAccountExistsWithThatId() {
    OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
    AccountId accountId = new AccountId(UUID.randomUUID());
    AccountRepository accounts = mock(AccountRepository.class);
    when(accounts.findById(accountId)).thenReturn(Optional.empty());
    GetAccountForOrganizationService service = new GetAccountForOrganizationService(accounts);

    Optional<Account> result =
        service.handle(new GetAccountForOrganizationQuery(organizationId, accountId));

    assertThat(result).isEmpty();
  }
}
