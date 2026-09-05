package com.clavaris.identity.application.usecase.completeforcedpasswordreset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.identity.application.usecase.issuerefreshtoken.RefreshTokenRepository;
import com.clavaris.identity.application.usecase.issuerefreshtoken.SessionRepository;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.registeraccount.PasswordHasher;
import com.clavaris.identity.application.usecase.registeraccount.WeakPasswordException;
import com.clavaris.identity.application.usecase.rotaterefreshtoken.AccountSessionRevoker;
import com.clavaris.identity.application.usecase.rotaterefreshtoken.AccountTokenRevoker;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CompleteForcedPasswordResetServiceTest {

  private final OrganizationId organizationId = new OrganizationId(UUID.randomUUID());

  private AccountRepository accounts;
  private SessionRepository sessions;
  private RefreshTokenRepository refreshTokens;
  private AccountTokenRevoker accountTokenRevoker;
  private AccountSessionRevoker accountSessionRevoker;
  private PasswordHasher hasher;
  private CompleteForcedPasswordResetService service;

  @BeforeEach
  void setUp() {
    accounts = mock(AccountRepository.class);
    sessions = mock(SessionRepository.class);
    refreshTokens = mock(RefreshTokenRepository.class);
    accountTokenRevoker = mock(AccountTokenRevoker.class);
    accountSessionRevoker = mock(AccountSessionRevoker.class);
    hasher = mock(PasswordHasher.class);
    service =
        new CompleteForcedPasswordResetService(
            accounts, sessions, refreshTokens, accountTokenRevoker, accountSessionRevoker, hasher);
    when(hasher.hash(anyString())).thenReturn("argon2id$new-hash");
  }

  @Test
  void resetsAnExistingCredentialAndRevokesEverythingForTheAccount() {
    Account account = Account.register(organizationId, new Email("account-holder@example.com"));
    account.attachPasswordCredential("argon2id$old-hash");
    account.requirePasswordReset();
    when(accounts.findById(account.id())).thenReturn(Optional.of(account));

    service.handle(new CompleteForcedPasswordResetCommand(account.id(), "a-Str0ng-Password!"));

    assertThat(account.passwordCredential().orElseThrow().passwordHash())
        .isEqualTo("argon2id$new-hash");
    assertThat(account.passwordResetRequiredAt()).isEmpty();
    verify(accounts).save(account);
    verify(sessions).revokeAllActiveForAccount(account.id());
    verify(refreshTokens).revokeAllActiveForAccount(account.id());
    verify(accountTokenRevoker).revokeAllTokensFor(account.id());
    verify(accountSessionRevoker).revokeAllSessionsFor(account.id());
  }

  @Test
  void attachesAFirstCredentialForAPasswordOptionalAccount() {
    // ADR-0024 §5 edge case: an account that signed up passwordless, forced to set its first
    // password rather than rotate a nonexistent one.
    Account account = Account.register(organizationId, new Email("passwordless@example.com"));
    account.requirePasswordReset();
    when(accounts.findById(account.id())).thenReturn(Optional.of(account));

    service.handle(new CompleteForcedPasswordResetCommand(account.id(), "a-Str0ng-Password!"));

    assertThat(account.passwordCredential()).isPresent();
    assertThat(account.passwordResetRequiredAt()).isEmpty();
  }

  @Test
  void rejectsAWeakNewPasswordWithoutTouchingTheAccount() {
    CompleteForcedPasswordResetCommand command =
        new CompleteForcedPasswordResetCommand(AccountId.newId(), "weak");

    assertThatExceptionOfType(WeakPasswordException.class)
        .isThrownBy(() -> service.handle(command));

    verify(accounts, never()).findById(any());
    verify(sessions, never()).revokeAllActiveForAccount(any());
  }

  @Test
  void rejectsAnUnknownAccount() {
    AccountId unknownAccountId = AccountId.newId();
    when(accounts.findById(unknownAccountId)).thenReturn(Optional.empty());
    CompleteForcedPasswordResetCommand command =
        new CompleteForcedPasswordResetCommand(unknownAccountId, "a-Str0ng-Password!");

    assertThatExceptionOfType(AccountNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(sessions, never()).revokeAllActiveForAccount(any());
  }
}
