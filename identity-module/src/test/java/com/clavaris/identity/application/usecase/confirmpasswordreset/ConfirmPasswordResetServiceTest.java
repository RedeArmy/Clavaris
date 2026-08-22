package com.clavaris.identity.application.usecase.confirmpasswordreset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.identity.application.usecase.issuerefreshtoken.RefreshTokenRepository;
import com.clavaris.identity.application.usecase.issuerefreshtoken.SessionRepository;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.registeraccount.EventOutboxWriter;
import com.clavaris.identity.application.usecase.registeraccount.PasswordHasher;
import com.clavaris.identity.application.usecase.registeraccount.WeakPasswordException;
import com.clavaris.identity.application.usecase.requestemailverification.VerificationTokenRepository;
import com.clavaris.identity.application.usecase.rotaterefreshtoken.AccountTokenRevoker;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.VerificationToken;
import com.clavaris.identity.domain.model.VerificationTokenType;
import com.clavaris.identity.domain.service.RefreshTokenSecret;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConfirmPasswordResetServiceTest {

  private final OrganizationId organizationId = new OrganizationId(UUID.randomUUID());

  private VerificationTokenRepository tokens;
  private AccountRepository accounts;
  private SessionRepository sessions;
  private RefreshTokenRepository refreshTokens;
  private AccountTokenRevoker accountTokenRevoker;
  private PasswordHasher hasher;
  private EventOutboxWriter outbox;
  private ConfirmPasswordResetService service;

  @BeforeEach
  void setUp() {
    tokens = mock(VerificationTokenRepository.class);
    accounts = mock(AccountRepository.class);
    sessions = mock(SessionRepository.class);
    refreshTokens = mock(RefreshTokenRepository.class);
    accountTokenRevoker = mock(AccountTokenRevoker.class);
    hasher = mock(PasswordHasher.class);
    outbox = mock(EventOutboxWriter.class);
    service =
        new ConfirmPasswordResetService(
            tokens, accounts, sessions, refreshTokens, accountTokenRevoker, hasher, outbox);
    when(hasher.hash(anyString())).thenReturn("argon2id$new-hash");
  }

  private Account accountWithPassword() {
    Account account = Account.register(organizationId, new Email("account-holder@example.com"));
    account.attachPasswordCredential("argon2id$old-hash");
    return account;
  }

  @Test
  void resetsThePasswordAndRevokesEverythingForTheAccount() {
    Account account = accountWithPassword();
    String rawToken = "a-valid-reset-token";
    VerificationToken token =
        VerificationToken.issue(
            account.id(),
            VerificationTokenType.PASSWORD_RESET,
            RefreshTokenSecret.hash(rawToken),
            Instant.now().plusSeconds(1800));
    when(tokens.findByTokenHash(RefreshTokenSecret.hash(rawToken))).thenReturn(Optional.of(token));
    when(accounts.findById(account.id())).thenReturn(Optional.of(account));

    service.handle(new ConfirmPasswordResetCommand(rawToken, "a-Str0ng-Password!"));

    assertThat(token.consumedAt()).isPresent();
    assertThat(account.passwordCredential().orElseThrow().passwordHash())
        .isEqualTo("argon2id$new-hash");
    verify(tokens).save(token);
    verify(accounts).save(account);
    verify(sessions).revokeAllActiveForAccount(account.id());
    verify(refreshTokens).revokeAllActiveForAccount(account.id());
    verify(accountTokenRevoker).revokeAllTokensFor(account.id());
    verify(outbox).write(eq("password_reset.completed"), eq(account.id()), any());
  }

  @Test
  void rejectsAWeakNewPasswordBeforeConsumingTheToken() {
    assertThatExceptionOfType(WeakPasswordException.class)
        .isThrownBy(() -> service.handle(new ConfirmPasswordResetCommand("any-token", "weak")));

    verify(tokens, never()).findByTokenHash(any());
    verify(sessions, never()).revokeAllActiveForAccount(any());
  }

  @Test
  void rejectsAnUnknownToken() {
    when(tokens.findByTokenHash(any())).thenReturn(Optional.empty());

    assertThatExceptionOfType(InvalidVerificationTokenException.class)
        .isThrownBy(
            () -> service.handle(new ConfirmPasswordResetCommand("garbage", "a-Str0ng-Password!")));

    verify(accounts, never()).save(any());
    verify(sessions, never()).revokeAllActiveForAccount(any());
  }

  @Test
  void rejectsATokenOfTheWrongType() {
    String rawToken = "an-email-verification-token";
    VerificationToken token =
        VerificationToken.issue(
            new AccountId(UUID.randomUUID()),
            VerificationTokenType.EMAIL_VERIFICATION,
            RefreshTokenSecret.hash(rawToken),
            Instant.now().plusSeconds(1800));
    when(tokens.findByTokenHash(RefreshTokenSecret.hash(rawToken))).thenReturn(Optional.of(token));

    assertThatExceptionOfType(InvalidVerificationTokenException.class)
        .isThrownBy(
            () -> service.handle(new ConfirmPasswordResetCommand(rawToken, "a-Str0ng-Password!")));

    verify(accounts, never()).save(any());
  }

  @Test
  void rejectsAnExpiredToken() {
    String rawToken = "expired-reset-token";
    VerificationToken token =
        VerificationToken.issue(
            new AccountId(UUID.randomUUID()),
            VerificationTokenType.PASSWORD_RESET,
            RefreshTokenSecret.hash(rawToken),
            Instant.now().minusSeconds(1));
    when(tokens.findByTokenHash(RefreshTokenSecret.hash(rawToken))).thenReturn(Optional.of(token));

    assertThatExceptionOfType(InvalidVerificationTokenException.class)
        .isThrownBy(
            () -> service.handle(new ConfirmPasswordResetCommand(rawToken, "a-Str0ng-Password!")));
  }
}
