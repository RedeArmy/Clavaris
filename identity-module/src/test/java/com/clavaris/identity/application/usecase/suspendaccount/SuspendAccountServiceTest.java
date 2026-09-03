package com.clavaris.identity.application.usecase.suspendaccount;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.issuerefreshtoken.RefreshTokenRepository;
import com.clavaris.identity.application.usecase.issuerefreshtoken.SessionRepository;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.registeraccount.EventOutboxWriter;
import com.clavaris.identity.application.usecase.rotaterefreshtoken.AccountSessionRevoker;
import com.clavaris.identity.application.usecase.rotaterefreshtoken.AccountTokenRevoker;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.AccountStatus;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SuspendAccountServiceTest {

  private static final AuditActor ACTOR = AuditActor.platformClient("test-client");

  private AccountRepository accounts;
  private SessionRepository sessions;
  private RefreshTokenRepository refreshTokens;
  private AccountTokenRevoker accountTokenRevoker;
  private AccountSessionRevoker accountSessionRevoker;
  private AuditEventRecorder auditEvents;
  private EventOutboxWriter outbox;
  private SuspendAccountService service;

  @BeforeEach
  void setUp() {
    accounts = mock(AccountRepository.class);
    sessions = mock(SessionRepository.class);
    refreshTokens = mock(RefreshTokenRepository.class);
    accountTokenRevoker = mock(AccountTokenRevoker.class);
    accountSessionRevoker = mock(AccountSessionRevoker.class);
    auditEvents = mock(AuditEventRecorder.class);
    outbox = mock(EventOutboxWriter.class);
    service =
        new SuspendAccountService(
            accounts,
            sessions,
            refreshTokens,
            accountTokenRevoker,
            accountSessionRevoker,
            auditEvents,
            outbox);
  }

  private Account registeredAccount() {
    Account account =
        Account.register(
            new OrganizationId(UUID.randomUUID()), new Email("suspend-me@example.com"));
    account.attachPasswordCredential("argon2id$hashed");
    when(accounts.findById(account.id())).thenReturn(Optional.of(account));
    return account;
  }

  @Test
  void suspendsTheAccountAndPersistsTheNewStatus() {
    Account account = registeredAccount();

    service.handle(new SuspendAccountCommand(account.id(), ACTOR));

    assertThat(account.status()).isEqualTo(AccountStatus.SUSPENDED);
    verify(accounts).save(account);
  }

  @Test
  void revokesLiveTokensAndSessionsImmediately() {
    Account account = registeredAccount();

    service.handle(new SuspendAccountCommand(account.id(), ACTOR));

    verify(accountTokenRevoker).revokeAllTokensFor(account.id());
    verify(accountSessionRevoker).revokeAllSessionsFor(account.id());
  }

  @Test
  void revokesEveryActiveSessionAndRefreshTokenRowSoAHeldTokenCannotKeepRotating() {
    // Real gap this test guards against (SDE-III review, 2026-09-03): a client already holding a
    // refresh token kept minting fresh access/refresh pairs after suspension, because this
    // module's own Session/RefreshToken rows were never revoked here — only the SAS token and the
    // hosted-login HttpSession were.
    Account account = registeredAccount();

    service.handle(new SuspendAccountCommand(account.id(), ACTOR));

    verify(sessions).revokeAllActiveForAccount(account.id());
    verify(refreshTokens).revokeAllActiveForAccount(account.id());
  }

  @Test
  void recordsAnAuditEventAndAnOutboxEvent() {
    Account account = registeredAccount();

    service.handle(new SuspendAccountCommand(account.id(), ACTOR));

    verify(auditEvents)
        .write(ACTOR, "account.suspended", "Account", account.id().value().toString(), null);
    verify(outbox).write(eq("account.suspended"), eq(account.id()), any(), any());
  }

  @Test
  void rejectsAnUnknownAccountWithoutRevokingOrRecordingAnything() {
    AccountId unknownAccountId = AccountId.newId();
    when(accounts.findById(unknownAccountId)).thenReturn(Optional.empty());
    SuspendAccountCommand command = new SuspendAccountCommand(unknownAccountId, ACTOR);

    assertThatExceptionOfType(AccountNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(accounts, never()).save(any());
    verifyNoInteractions(sessions);
    verifyNoInteractions(refreshTokens);
    verifyNoInteractions(accountTokenRevoker);
    verifyNoInteractions(accountSessionRevoker);
    verifyNoInteractions(auditEvents);
    verifyNoInteractions(outbox);
  }
}
