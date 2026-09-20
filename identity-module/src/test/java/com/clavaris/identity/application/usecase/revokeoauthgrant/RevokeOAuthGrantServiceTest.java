package com.clavaris.identity.application.usecase.revokeoauthgrant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.listoauthgrantsforaccount.OAuthGrantsRepository;
import com.clavaris.identity.domain.model.AccountId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RevokeOAuthGrantServiceTest {

  private static final AuditActor ACTOR = AuditActor.platformAccount(UUID.randomUUID());

  private OAuthGrantsRepository grants;
  private AuditEventRecorder auditEvents;
  private RevokeOAuthGrantService service;
  private AccountId accountId;

  @BeforeEach
  void setUp() {
    grants = mock(OAuthGrantsRepository.class);
    auditEvents = mock(AuditEventRecorder.class);
    service = new RevokeOAuthGrantService(grants, auditEvents);
    accountId = AccountId.newId();
  }

  @Test
  void deletesTheGrantAndAuditsWhenItBelongsToTheAccount() {
    when(grants.revokeById(accountId, "auth-id")).thenReturn(true);

    service.handle(new RevokeOAuthGrantCommand(accountId, "auth-id", ACTOR));

    verify(auditEvents)
        .write(ACTOR, "account.oauth_grant_revoked", "OAuth2Authorization", "auth-id", null);
  }

  @Test
  void throwsAndNeverAuditsWhenNoMatchingGrantExists() {
    when(grants.revokeById(accountId, "auth-id")).thenReturn(false);
    RevokeOAuthGrantCommand command = new RevokeOAuthGrantCommand(accountId, "auth-id", ACTOR);

    assertThatExceptionOfType(OAuthGrantNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verifyNoInteractions(auditEvents);
  }

  @Test
  void auditFailureDoesNotPropagate() {
    when(grants.revokeById(accountId, "auth-id")).thenReturn(true);
    doThrow(new RuntimeException("audit store down"))
        .when(auditEvents)
        .write(any(), anyString(), anyString(), anyString(), isNull());

    assertThatCode(() -> service.handle(new RevokeOAuthGrantCommand(accountId, "auth-id", ACTOR)))
        .doesNotThrowAnyException();
  }
}
