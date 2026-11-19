package com.clavaris.app.infrastructure.adapter.out.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

/** TD-PERF-043: the rows go in the caller's transaction, the slow follow-up only on request. */
class OrganizationIdentityDataEraserBridgeTest {

  private final AccountRepository accounts = mock(AccountRepository.class);
  private final SigningKeyRepository signingKeys = mock(SigningKeyRepository.class);
  private final OrganizationSigningKeyMaterialFactory keyMaterial =
      mock(OrganizationSigningKeyMaterialFactory.class);
  private final AccountSessionRevoker sessionRevoker = mock(AccountSessionRevoker.class);
  private final io.micrometer.core.instrument.simple.SimpleMeterRegistry meters =
      new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
  private final PlatformTransactionManager transactionManager =
      mock(PlatformTransactionManager.class);
  private final OrganizationIdentityDataEraserBridge bridge =
      new OrganizationIdentityDataEraserBridge(
          accounts, signingKeys, keyMaterial, sessionRevoker, transactionManager, meters);

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

    // TD-SEC-102: the purge needs neither Redis nor a database, so it goes first.
    InOrder order = inOrder(sessionRevoker, keyMaterial);
    order.verify(keyMaterial).purgeAllFor(orgId, List.of("kid-1", "kid-2"));
    order.verify(sessionRevoker).revokeAllSessionsFor(first);
    order.verify(sessionRevoker).revokeAllSessionsFor(second);
  }

  private Runnable followUpFor(final UUID organizationId, final AccountId... accountIds) {
    OrganizationId orgId = new OrganizationId(organizationId);
    when(accounts.findAllAccountIdsByOrganizationId(orgId)).thenReturn(List.of(accountIds));
    when(signingKeys.findAllKidsByOrganizationId(orgId)).thenReturn(List.of("kid-1"));
    return bridge.eraseAllFor(organizationId);
  }

  // TD-SEC-102: with Redis down the revoker failed and the purge never ran.
  @Test
  void aFailureClosingSessionsDoesNotSkipThePurgeNorTheOtherAccounts() {
    UUID organizationId = UUID.randomUUID();
    AccountId first = new AccountId(UUID.randomUUID());
    AccountId second = new AccountId(UUID.randomUUID());
    Runnable followUp = followUpFor(organizationId, first, second);
    doThrow(new IllegalStateException("redis down"))
        .when(sessionRevoker)
        .revokeAllSessionsFor(first);

    assertThatCode(followUp::run).doesNotThrowAnyException();

    verify(keyMaterial).purgeAllFor(new OrganizationId(organizationId), List.of("kid-1"));
    verify(sessionRevoker).revokeAllSessionsFor(second);
  }

  @Test
  void aFailurePurgingKeysDoesNotSkipClosingTheSessions() {
    UUID organizationId = UUID.randomUUID();
    AccountId only = new AccountId(UUID.randomUUID());
    Runnable followUp = followUpFor(organizationId, only);
    doThrow(new IllegalStateException("disk")).when(keyMaterial).purgeAllFor(any(), any());

    assertThatCode(followUp::run).doesNotThrowAnyException();

    verify(sessionRevoker).revokeAllSessionsFor(only);
    // TD-PROC-036: the failure is counted, so the alert can say private keys may be on disk.
    assertThat(meters.get("clavaris.organization.key_purge.failed").counter().count())
        .isEqualTo(1.0);
  }

  // The write that records a deferred closure happens after the original transaction is over, so
  // each account's closing gets a transaction of its own.
  @Test
  void eachAccountsSessionsAreClosedInATransactionOfItsOwn() {
    UUID organizationId = UUID.randomUUID();
    Runnable followUp =
        followUpFor(
            organizationId, new AccountId(UUID.randomUUID()), new AccountId(UUID.randomUUID()));

    followUp.run();

    ArgumentCaptor<TransactionDefinition> definitions =
        ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(transactionManager, times(2)).getTransaction(definitions.capture());
    assertThat(definitions.getAllValues())
        .allSatisfy(
            definition ->
                assertThat(definition.getPropagationBehavior())
                    .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW));
  }
}
