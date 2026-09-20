package com.clavaris.identity.application.usecase.revokealloauthgrantsforaccount;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.rotaterefreshtoken.AccountTokenRevoker;
import com.clavaris.identity.domain.model.AccountId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RevokeAllOAuthGrantsForAccountServiceTest {

  private static final AuditActor ACTOR = AuditActor.platformAccount(UUID.randomUUID());

  private AccountTokenRevoker tokenRevoker;
  private AuditEventRecorder auditEvents;
  private RevokeAllOAuthGrantsForAccountService service;
  private AccountId accountId;

  @BeforeEach
  void setUp() {
    tokenRevoker = mock(AccountTokenRevoker.class);
    auditEvents = mock(AuditEventRecorder.class);
    service = new RevokeAllOAuthGrantsForAccountService(tokenRevoker, auditEvents);
    accountId = AccountId.newId();
  }

  @Test
  void revokesEveryTokenAndAudits() {
    service.handle(new RevokeAllOAuthGrantsForAccountCommand(accountId, ACTOR));

    verify(tokenRevoker).revokeAllTokensFor(accountId);
    verify(auditEvents)
        .write(
            ACTOR,
            "account.oauth_grants_revoked_all",
            "Account",
            accountId.value().toString(),
            null);
  }

  @Test
  void auditFailureDoesNotPropagate() {
    doThrow(new RuntimeException("audit store down"))
        .when(auditEvents)
        .write(any(), anyString(), anyString(), anyString(), isNull());

    assertThatCode(
            () -> service.handle(new RevokeAllOAuthGrantsForAccountCommand(accountId, ACTOR)))
        .doesNotThrowAnyException();
    verify(tokenRevoker).revokeAllTokensFor(accountId);
  }
}
