package com.clavaris.app.infrastructure.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.identity.application.usecase.activatesigningkeyfororganization.SigningKeyRepository;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.rotaterefreshtoken.AccountSessionRevoker;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class OrganizationIdentityDataEraserBridgeTest {

  private final AccountRepository accounts = mock(AccountRepository.class);
  private final SigningKeyRepository signingKeys = mock(SigningKeyRepository.class);
  private final AccountSessionRevoker accountSessionRevoker = mock(AccountSessionRevoker.class);
  private final OrganizationIdentityDataEraserBridge bridge =
      new OrganizationIdentityDataEraserBridge(accounts, signingKeys, accountSessionRevoker);

  @Test
  void revokesEveryAccountsLiveSessionBeforeBulkDeletingAccountsAndSigningKeys() {
    UUID organizationId = UUID.randomUUID();
    OrganizationId orgId = new OrganizationId(organizationId);
    Account first = Account.register(orgId, new Email("first@example.com"));
    Account second = Account.register(orgId, new Email("second@example.com"));
    when(accounts.findAllByOrganizationId(orgId)).thenReturn(List.of(first, second));

    bridge.eraseAllFor(organizationId);

    // TD-SEC-031: each Account's own live HttpSession must be revoked before the bulk delete —
    // once the row is gone there is no per-account hook left to drive this loop from.
    InOrder order = inOrder(accountSessionRevoker, accounts);
    order.verify(accountSessionRevoker).revokeAllSessionsFor(first.id());
    order.verify(accountSessionRevoker).revokeAllSessionsFor(second.id());
    order.verify(accounts).deleteAllByOrganizationId(orgId);
    verify(signingKeys).deleteAllByOrganizationId(orgId);
  }

  @Test
  void revokesNothingWhenTheOrganizationOwnsNoAccounts() {
    UUID organizationId = UUID.randomUUID();
    when(accounts.findAllByOrganizationId(any())).thenReturn(List.of());

    bridge.eraseAllFor(organizationId);

    verify(accounts).deleteAllByOrganizationId(new OrganizationId(organizationId));
    verify(signingKeys).deleteAllByOrganizationId(new OrganizationId(organizationId));
  }
}
