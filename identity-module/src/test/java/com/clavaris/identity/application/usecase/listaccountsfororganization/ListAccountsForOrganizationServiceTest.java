package com.clavaris.identity.application.usecase.listaccountsfororganization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clavaris.common.domain.model.KeysetPage;
import com.clavaris.common.domain.model.KeysetPageRequest;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ListAccountsForOrganizationServiceTest {

  @Test
  void delegatesToTheRepositoryForTheGivenOrganizationAndPageRequest() {
    OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
    KeysetPageRequest pageRequest = KeysetPageRequest.first();
    AccountRepository accounts = mock(AccountRepository.class);
    Account account = Account.register(organizationId, new Email("ada@example.com"));
    KeysetPage<Account> page = new KeysetPage<>(List.of(account), null, null, false, false);
    when(accounts.findKeysetPageByOrganizationId(organizationId, pageRequest)).thenReturn(page);
    ListAccountsForOrganizationService service = new ListAccountsForOrganizationService(accounts);

    KeysetPage<Account> result =
        service.handle(new ListAccountsForOrganizationQuery(organizationId, pageRequest));

    assertThat(result).isSameAs(page);
  }
}
