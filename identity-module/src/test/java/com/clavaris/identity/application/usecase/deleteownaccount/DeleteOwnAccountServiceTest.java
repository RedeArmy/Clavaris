package com.clavaris.identity.application.usecase.deleteownaccount;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.deleteaccount.AccountNotFoundException;
import com.clavaris.identity.application.usecase.deleteaccount.DeleteAccountCommand;
import com.clavaris.identity.application.usecase.deleteaccount.DeleteAccountUseCase;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.AccountStatus;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DeleteOwnAccountServiceTest {

  private AccountRepository accounts;
  private DeleteAccountUseCase deleteAccount;
  private DeleteOwnAccountService service;
  private Account account;

  @BeforeEach
  void setUp() {
    accounts = mock(AccountRepository.class);
    deleteAccount = mock(DeleteAccountUseCase.class);
    service = new DeleteOwnAccountService(accounts, deleteAccount);
    account =
        Account.reconstitute(
            AccountId.newId(),
            new OrganizationId(UUID.randomUUID()),
            new Email("ada@example.com"),
            Instant.now(),
            null,
            AccountStatus.ACTIVE,
            null,
            null,
            null);
  }

  @Test
  void delegatesToDeleteAccountWithSelfActorWhenAllowed() {
    account.allowSelfDelete();
    when(accounts.findById(account.id())).thenReturn(Optional.of(account));

    service.handle(account.id());

    final ArgumentCaptor<DeleteAccountCommand> captor =
        ArgumentCaptor.forClass(DeleteAccountCommand.class);
    verify(deleteAccount).handle(captor.capture());
    assertThat(captor.getValue().accountId()).isEqualTo(account.id());
    assertThat(captor.getValue().actor()).isEqualTo(AuditActor.account(account.id().value()));
  }

  @Test
  void throwsSelfDeleteNotAllowedWhenAccountHasNotOptedIn() {
    when(accounts.findById(account.id())).thenReturn(Optional.of(account));
    AccountId id = account.id();

    assertThatExceptionOfType(SelfDeleteNotAllowedException.class)
        .isThrownBy(() -> service.handle(id));

    verify(deleteAccount, never()).handle(any());
  }

  @Test
  void throwsAccountNotFoundWhenAccountDoesNotExist() {
    final AccountId missing = AccountId.newId();
    when(accounts.findById(missing)).thenReturn(Optional.empty());

    assertThatExceptionOfType(AccountNotFoundException.class)
        .isThrownBy(() -> service.handle(missing));

    verify(deleteAccount, never()).handle(any());
  }
}
