package com.clavaris.identity.application.usecase.changepassword;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.authenticatewithpassword.PasswordVerifier;
import com.clavaris.identity.application.usecase.issuerefreshtoken.RefreshTokenRepository;
import com.clavaris.identity.application.usecase.issuerefreshtoken.SessionRepository;
import com.clavaris.identity.application.usecase.listactivesessionsforaccount.AccountActiveSessionsRepository;
import com.clavaris.identity.application.usecase.listactivesessionsforaccount.ActiveAccountSession;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.registeraccount.BreachedPasswordChecker;
import com.clavaris.identity.application.usecase.registeraccount.BreachedPasswordException;
import com.clavaris.identity.application.usecase.registeraccount.PasswordHasher;
import com.clavaris.identity.application.usecase.registeraccount.WeakPasswordException;
import com.clavaris.identity.application.usecase.requestemailverification.MailSender;
import com.clavaris.identity.application.usecase.rotaterefreshtoken.AccountTokenRevoker;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChangePasswordServiceTest {

  private static final String KEEP = "session-in-use";
  private static final String GOOD_NEW = "a-brand-new-passphrase-42";

  private final OrganizationId organizationId = new OrganizationId(UUID.randomUUID());

  private AccountRepository accounts;
  private PasswordVerifier verifier;
  private PasswordHasher hasher;
  private BreachedPasswordChecker breached;
  private SessionRepository sessions;
  private RefreshTokenRepository refreshTokens;
  private AccountTokenRevoker tokenRevoker;
  private AccountActiveSessionsRepository activeSessions;
  private AuditEventRecorder audit;
  private MailSender mail;
  private ChangePasswordService service;
  private Account account;

  @BeforeEach
  void setUp() {
    accounts = mock(AccountRepository.class);
    verifier = mock(PasswordVerifier.class);
    hasher = mock(PasswordHasher.class);
    breached = mock(BreachedPasswordChecker.class);
    sessions = mock(SessionRepository.class);
    refreshTokens = mock(RefreshTokenRepository.class);
    tokenRevoker = mock(AccountTokenRevoker.class);
    activeSessions = mock(AccountActiveSessionsRepository.class);
    audit = mock(AuditEventRecorder.class);
    mail = mock(MailSender.class);
    service =
        new ChangePasswordService(
            accounts,
            verifier,
            hasher,
            breached,
            sessions,
            refreshTokens,
            tokenRevoker,
            activeSessions,
            audit,
            mail);
    account = Account.register(organizationId, new Email("holder@example.com"));
    account.attachPasswordCredential("argon2id$old");
    when(accounts.findById(account.id())).thenReturn(Optional.of(account));
    when(verifier.matches("current-secret", "argon2id$old")).thenReturn(true);
    when(hasher.hash(anyString())).thenReturn("argon2id$new");
    when(breached.isBreached(any())).thenReturn(false);
  }

  private ChangePasswordCommand command(final String current, final String next) {
    return new ChangePasswordCommand(
        account.id(), current, next, KEEP, AuditActor.account(account.id().value()));
  }

  @Test
  void changesThePasswordAndEndsEverythingExceptTheSessionInUse() {
    final Instant at = Instant.now();
    when(activeSessions.findAllByAccountId(account.id()))
        .thenReturn(
            List.of(
                new ActiveAccountSession(KEEP, "ua", "ip", at, at),
                new ActiveAccountSession("other", "ua", "ip", at, at)));

    service.handle(command("current-secret", GOOD_NEW));

    assertThat(account.passwordCredential().orElseThrow().passwordHash()).isEqualTo("argon2id$new");
    verify(accounts).saveCredential(account);
    verify(sessions).revokeAllActiveForAccount(account.id());
    verify(refreshTokens).revokeAllActiveForAccount(account.id());
    verify(tokenRevoker).revokeAllTokensFor(account.id());
    verify(activeSessions).revoke("other");
    verify(activeSessions, never()).revoke(KEEP);
    verify(mail).sendPasswordChangedNotice("holder@example.com", organizationId);
  }

  @Test
  void aWrongCurrentPasswordChangesNothing() {
    assertThatExceptionOfType(IncorrectCurrentPasswordException.class)
        .isThrownBy(() -> service.handle(command("wrong", GOOD_NEW)));

    verify(accounts, never()).save(any());
    verify(sessions, never()).revokeAllActiveForAccount(any());
    verify(mail, never()).sendPasswordChangedNotice(any(), any());
  }

  @Test
  void aWeakNewPasswordIsRejectedBeforeAnythingChanges() {
    assertThatExceptionOfType(WeakPasswordException.class)
        .isThrownBy(() -> service.handle(command("current-secret", "short")));

    verify(accounts, never()).save(any());
  }

  @Test
  void aBreachedNewPasswordIsRejectedBeforeAnythingChanges() {
    when(breached.isBreached(GOOD_NEW)).thenReturn(true);

    assertThatExceptionOfType(BreachedPasswordException.class)
        .isThrownBy(() -> service.handle(command("current-secret", GOOD_NEW)));

    verify(accounts, never()).save(any());
  }

  @Test
  void anAccountWithoutAPasswordHasNothingToChange() {
    final Account socialOnly = Account.register(organizationId, new Email("social@example.com"));
    when(accounts.findById(socialOnly.id())).thenReturn(Optional.of(socialOnly));

    assertThatExceptionOfType(NoPasswordToChangeException.class)
        .isThrownBy(
            () ->
                service.handle(
                    new ChangePasswordCommand(
                        socialOnly.id(),
                        "x",
                        GOOD_NEW,
                        KEEP,
                        AuditActor.account(socialOnly.id().value()))));
  }

  @Test
  void aFailureToSendTheNoticeDoesNotUndoTheChange() {
    doThrow(new IllegalStateException("smtp down"))
        .when(mail)
        .sendPasswordChangedNotice(any(), any());
    when(activeSessions.findAllByAccountId(account.id())).thenReturn(List.of());

    service.handle(command("current-secret", GOOD_NEW));

    assertThat(account.passwordCredential().orElseThrow().passwordHash()).isEqualTo("argon2id$new");
  }
}
