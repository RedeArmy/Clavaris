package com.clavaris.identity.application.usecase.authenticateplatformaccountwithpassword;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clavaris.identity.application.usecase.authenticatewithpassword.PasswordVerifier;
import com.clavaris.identity.application.usecase.registerplatformaccount.PlatformAccountRepository;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.PlatformAccount;
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

    assertThatExceptionOfType(InvalidPlatformCredentialsException.class)
        .isThrownBy(
            () ->
                service.handle(
                    new AuthenticatePlatformAccountWithPasswordCommand(email, RAW_PASSWORD)));
  }

  @Test
  void rejectsAWrongPassword() {
    PlatformAccount account = accountWithCredential();
    when(accounts.findByEmail(email)).thenReturn(Optional.of(account));
    when(verifier.matches("wrong-password", "stored-hash")).thenReturn(false);

    assertThatExceptionOfType(InvalidPlatformCredentialsException.class)
        .isThrownBy(
            () ->
                service.handle(
                    new AuthenticatePlatformAccountWithPasswordCommand(email, "wrong-password")));
  }
}
