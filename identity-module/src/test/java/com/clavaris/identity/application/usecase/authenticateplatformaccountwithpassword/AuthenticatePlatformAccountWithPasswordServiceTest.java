package com.clavaris.identity.application.usecase.authenticateplatformaccountwithpassword;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clavaris.identity.application.usecase.authenticatewithpassword.PasswordVerifier;
import com.clavaris.identity.application.usecase.registerplatformaccount.PlatformAccountRepository;
import com.clavaris.identity.domain.model.AccountStatus;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.PlatformAccount;
import com.clavaris.identity.domain.model.PlatformAccountId;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuthenticatePlatformAccountWithPasswordServiceTest {

  private static final String RAW_PASSWORD = "a-correct-password";

  private final Email email = new Email("founder@example.com");

  private PlatformAccountRepository accounts;
  private PasswordVerifier verifier;
  private AuthenticatePlatformAccountWithPasswordService service;

  @BeforeEach
  void setUp() {
    accounts = mock(PlatformAccountRepository.class);
    verifier = mock(PasswordVerifier.class);
    service = new AuthenticatePlatformAccountWithPasswordService(accounts, verifier);
  }

  private PlatformAccount accountWithCredential() {
    PlatformAccount account = PlatformAccount.register(email);
    account.attachPasswordCredential("stored-hash");
    return account;
  }

  @Test
  void returnsTheAccountIdOnAMatchingPassword() {
    PlatformAccount account = accountWithCredential();
    when(accounts.findByEmail(email)).thenReturn(Optional.of(account));
    when(verifier.matches(RAW_PASSWORD, "stored-hash")).thenReturn(true);

    var id =
        service.handle(new AuthenticatePlatformAccountWithPasswordCommand(email, RAW_PASSWORD));

    assertThat(id).isEqualTo(account.id());
  }

  @Test
  void rejectsAnUnknownEmail() {
    when(accounts.findByEmail(email)).thenReturn(Optional.empty());
    AuthenticatePlatformAccountWithPasswordCommand command =
        new AuthenticatePlatformAccountWithPasswordCommand(email, RAW_PASSWORD);

    assertThatExceptionOfType(InvalidPlatformCredentialsException.class)
        .isThrownBy(() -> service.handle(command));
  }

  @Test
  void rejectsAWrongPassword() {
    PlatformAccount account = accountWithCredential();
    when(accounts.findByEmail(email)).thenReturn(Optional.of(account));
    when(verifier.matches("wrong-password", "stored-hash")).thenReturn(false);
    AuthenticatePlatformAccountWithPasswordCommand command =
        new AuthenticatePlatformAccountWithPasswordCommand(email, "wrong-password");

    assertThatExceptionOfType(InvalidPlatformCredentialsException.class)
        .isThrownBy(() -> service.handle(command));
  }

  @Test
  void rejectsASuspendedAccountEvenWithTheCorrectPassword() {
    PlatformAccount account =
        PlatformAccount.reconstitute(
            PlatformAccountId.newId(), email, Instant.now(), null, AccountStatus.SUSPENDED, null);
    when(accounts.findByEmail(email)).thenReturn(Optional.of(account));
    AuthenticatePlatformAccountWithPasswordCommand command =
        new AuthenticatePlatformAccountWithPasswordCommand(email, RAW_PASSWORD);

    assertThatExceptionOfType(InvalidPlatformCredentialsException.class)
        .isThrownBy(() -> service.handle(command));
  }

  @Test
  void rejectsAnAccountWithNoPasswordCredentialAttached() {
    PlatformAccount account =
        PlatformAccount.reconstitute(
            PlatformAccountId.newId(), email, Instant.now(), null, AccountStatus.ACTIVE, null);
    when(accounts.findByEmail(email)).thenReturn(Optional.of(account));
    AuthenticatePlatformAccountWithPasswordCommand command =
        new AuthenticatePlatformAccountWithPasswordCommand(email, RAW_PASSWORD);

    assertThatExceptionOfType(InvalidPlatformCredentialsException.class)
        .isThrownBy(() -> service.handle(command));
  }
}
