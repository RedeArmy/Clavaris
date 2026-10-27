package com.clavaris.app.infrastructure.adapter.out.bridge;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.clavaris.identity.application.usecase.activatesigningkeyfororganization.SigningKeyRepository;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.rotaterefreshtoken.AccountSessionRevoker;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.infrastructure.adapter.out.security.OrganizationSigningKeyMaterialFactory;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/** TD-PERF-043: the rows go in the caller's transaction, the slow follow-up only on request. */
class OrganizationIdentityDataEraserBridgeTest {

  private final AccountRepository accounts = mock(AccountRepository.class);
  private final SigningKeyRepository signingKeys = mock(SigningKeyRepository.class);
  private final OrganizationSigningKeyMaterialFactory keyMaterial =
      mock(OrganizationSigningKeyMaterialFactory.class);
  private final AccountSessionRevoker sessionRevoker = mock(AccountSessionRevoker.class);
  private final OrganizationIdentityDataEraserBridge bridge =
      new OrganizationIdentityDataEraserBridge(accounts, signingKeys, keyMaterial, sessionRevoker);

  @Test
  void deletesTheRowsNowButLeavesSessionsAndKeyFilesForTheReturnedAction() {
    UUID organizationId = UUID.randomUUID();
    OrganizationId orgId = new OrganizationId(organizationId);
    AccountId first = new AccountId(UUID.randomUUID());
    AccountId second = new AccountId(UUID.randomUUID());
    when(accounts.findAllAccountIdsByOrganizationId(orgId)).thenReturn(List.of(first, second));
    when(signingKeys.findAllKidsByOrganizationId(orgId)).thenReturn(List.of("kid-1", "kid-2"));

    Runnable followUp = bridge.eraseAllFor(organizationId);

    verify(accounts).deleteAllByOrganizationId(orgId);
    verify(signingKeys).deleteAllByOrganizationId(orgId);
    verifyNoInteractions(sessionRevoker, keyMaterial);

    followUp.run();

    InOrder order = inOrder(sessionRevoker, keyMaterial);
    order.verify(sessionRevoker).revokeAllSessionsFor(first);
    order.verify(sessionRevoker).revokeAllSessionsFor(second);
    order.verify(keyMaterial).purgeAllFor(orgId, List.of("kid-1", "kid-2"));
  }
}
