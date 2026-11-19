package com.clavaris.identity.application.usecase.getaccountstatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.AccountStatus;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

// TD-FUT-081: one column read, nothing written, nothing else loaded.
class GetAccountStatusServiceTest {

  private final AccountRepository accounts = mock(AccountRepository.class);
  private final GetAccountStatusService service = new GetAccountStatusService(accounts);
  private final AccountId accountId = new AccountId(UUID.randomUUID());

  @ParameterizedTest
  @EnumSource(
      value = AccountStatus.class,
      names = {"ACTIVE", "SUSPENDED", "BANNED", "PENDING_APPROVAL", "REJECTED"})
  void returnsTheStatusTheAccountIsIn(final AccountStatus status) {
    when(accounts.findStatusById(accountId)).thenReturn(Optional.of(status));

    assertThat(service.handle(new GetAccountStatusQuery(accountId))).contains(status);
  }

  @Test
  void isEmptyWhenThereIsNoSuchAccount() {
    when(accounts.findStatusById(accountId)).thenReturn(Optional.empty());

    assertThat(service.handle(new GetAccountStatusQuery(accountId))).isEmpty();
  }

  @Test
  void readsOnlyTheStatusAndNeverLoadsTheAccount() {
    when(accounts.findStatusById(accountId)).thenReturn(Optional.of(AccountStatus.ACTIVE));

    service.handle(new GetAccountStatusQuery(accountId));

    verify(accounts).findStatusById(accountId);
    verifyNoMoreInteractions(accounts);
  }
}
