package com.clavaris.identity.application.usecase.listpendingaccountsfororganization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ListPendingAccountsForOrganizationServiceTest {

  private final OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
  private AccountRepository accounts;
  private ListPendingAccountsForOrganizationService service;

  @BeforeEach
  void setUp() {
    accounts = mock(AccountRepository.class);
    service = new ListPendingAccountsForOrganizationService(accounts);
  }

  @Test
  void theQueueComesWithItsTotal() {
    final Account waiting =
        Account.registerPendingApproval(organizationId, new Email("a@x.test"), Instant.now());
    when(accounts.countPendingApprovalByOrganizationId(organizationId)).thenReturn(7L);
    when(accounts.findPendingApprovalByOrganizationId(organizationId, 5))
        .thenReturn(List.of(waiting));

    final var result =
        service.handle(new ListPendingAccountsForOrganizationQuery(organizationId, 5));

    assertThat(result.accounts()).containsExactly(waiting);
    assertThat(result.total()).isEqualTo(7);
  }

  @Test
  void aLimitOfZeroAsksOnlyForTheCountAndReadsNoAccounts() {
    when(accounts.countPendingApprovalByOrganizationId(organizationId)).thenReturn(4L);

    final var result =
        service.handle(new ListPendingAccountsForOrganizationQuery(organizationId, 0));

    assertThat(result.accounts()).isEmpty();
    assertThat(result.total()).isEqualTo(4);
    verify(accounts, never()).findPendingApprovalByOrganizationId(organizationId, 0);
  }

  @Test
  void nobodyWaitingReadsNoAccountsEither() {
    when(accounts.countPendingApprovalByOrganizationId(organizationId)).thenReturn(0L);

    final var result =
        service.handle(new ListPendingAccountsForOrganizationQuery(organizationId, 50));

    assertThat(result.accounts()).isEmpty();
    verify(accounts, never()).findPendingApprovalByOrganizationId(eq(organizationId), anyInt());
  }

  @Test
  void aNegativeLimitIsRefused() {
    assertThatThrownBy(() -> new ListPendingAccountsForOrganizationQuery(organizationId, -1))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
