package com.clavaris.identity.application.usecase.getaccountprofile;

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

class GetAccountProfileServiceTest {

  @Test
  void returnsTheAccountById() {
    Account account =
        Account.register(new OrganizationId(UUID.randomUUID()), new Email("ada@example.com"));
    AccountRepository accounts = mock(AccountRepository.class);
    when(accounts.findById(account.id())).thenReturn(Optional.of(account));
    GetAccountProfileService service = new GetAccountProfileService(accounts);

    Optional<Account> result = service.handle(new GetAccountProfileQuery(account.id()));

    assertThat(result).contains(account);
  }

  @Test
  void isEmptyWhenNoAccountExistsWithThatId() {
    AccountId accountId = new AccountId(UUID.randomUUID());
    AccountRepository accounts = mock(AccountRepository.class);
    when(accounts.findById(accountId)).thenReturn(Optional.empty());
    GetAccountProfileService service = new GetAccountProfileService(accounts);

    Optional<Account> result = service.handle(new GetAccountProfileQuery(accountId));

    assertThat(result).isEmpty();
  }
}
