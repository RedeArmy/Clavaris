package com.clavaris.app.infrastructure.adapter.out.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clavaris.common.domain.model.KeysetCursor;
import com.clavaris.common.domain.model.KeysetPage;
import com.clavaris.identity.application.usecase.listaccountsfororganization.ListAccountsForOrganizationUseCase;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.organization.application.usecase.assignworkspaceroletoaccount.OrganizationAccountSummary;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Proves the one nontrivial piece of logic this bridge adds over a plain delegate call: looping
 * every page of {@link ListAccountsForOrganizationUseCase} into one flat list — see the bridge's
 * own Javadoc for why "every account," not a page of them, is this port's actual contract.
 */
class OrganizationAccountDirectoryBridgeTest {

  private final ListAccountsForOrganizationUseCase listAccounts =
      mock(ListAccountsForOrganizationUseCase.class);
  private final OrganizationAccountDirectoryBridge bridge =
      new OrganizationAccountDirectoryBridge(listAccounts);

  @Test
  void returnsEveryAccountAcrossMultiplePages() {
    UUID organizationId = UUID.randomUUID();
    Account first = anAccount(organizationId, "ada@example.com");
    Account second = anAccount(organizationId, "bo@example.com");
    KeysetCursor cursor = new KeysetCursor(Instant.now(), first.id().value());
    when(listAccounts.handle(any()))
        .thenReturn(new KeysetPage<>(List.of(first), cursor, cursor, true, false))
        .thenReturn(new KeysetPage<>(List.of(second), cursor, cursor, false, true));

    List<OrganizationAccountSummary> summaries = bridge.listAccountsForOrganization(organizationId);

    assertThat(summaries)
        .containsExactlyInAnyOrder(
            new OrganizationAccountSummary(first.id().value(), "ada@example.com"),
            new OrganizationAccountSummary(second.id().value(), "bo@example.com"));
  }

  @Test
  void returnsAnEmptyListForAnOrganizationWithNoAccounts() {
    UUID organizationId = UUID.randomUUID();
    when(listAccounts.handle(any()))
        .thenReturn(new KeysetPage<>(List.of(), null, null, false, false));

    List<OrganizationAccountSummary> summaries = bridge.listAccountsForOrganization(organizationId);

    assertThat(summaries).isEmpty();
  }

  private static Account anAccount(final UUID organizationId, final String email) {
    return Account.register(new OrganizationId(organizationId), new Email(email));
  }
}
