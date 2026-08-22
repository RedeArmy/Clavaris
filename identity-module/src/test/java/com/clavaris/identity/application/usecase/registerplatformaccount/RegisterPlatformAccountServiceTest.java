package com.clavaris.identity.application.usecase.registerplatformaccount;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.identity.application.usecase.registeraccount.PasswordHasher;
import com.clavaris.identity.application.usecase.registeraccount.WeakPasswordException;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.PlatformAccountId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class RegisterPlatformAccountServiceTest {

  private static final String VALID_PASSWORD = "a-valid-password";

  private final Email email = new Email("founder@example.com");

  private PlatformAccountRepository accounts;
  private PasswordHasher hasher;
  private RegisterPlatformAccountService service;

  @BeforeEach
  void setUp() {
    accounts = mock(PlatformAccountRepository.class);
    hasher = mock(PasswordHasher.class);
    service = new RegisterPlatformAccountService(accounts, hasher);

    when(hasher.hash(anyString())).thenReturn("hashed-password");
  }

  @Test
  void registersAndReturnsANewPlatformAccountId() {
    when(accounts.existsByEmail(email)).thenReturn(false);

    PlatformAccountId id =
        service.handle(new RegisterPlatformAccountCommand(email, VALID_PASSWORD));

    assertThat(id).isNotNull();
    verify(accounts).save(any());
  }

  @Test
  void hashesTheRawPasswordBeforeSaving_neverPersistsItRaw() {
    when(accounts.existsByEmail(email)).thenReturn(false);

    service.handle(new RegisterPlatformAccountCommand(email, VALID_PASSWORD));

    verify(hasher).hash(VALID_PASSWORD);
  }

  @Test
  void rejectsAPasswordThatFailsPolicy_beforeTouchingTheRepository() {
    RegisterPlatformAccountCommand command = new RegisterPlatformAccountCommand(email, "short");

    assertThatExceptionOfType(WeakPasswordException.class)
        .isThrownBy(() -> service.handle(command));

    verify(accounts, never()).save(any());
  }

  @Test
  void rejectsRegistrationWhenThePreCheckFindsTheEmailAlreadyTaken() {
    when(accounts.existsByEmail(email)).thenReturn(true);
    RegisterPlatformAccountCommand command =
        new RegisterPlatformAccountCommand(email, VALID_PASSWORD);

    assertThatExceptionOfType(PlatformAccountEmailAlreadyRegisteredException.class)
        .isThrownBy(() -> service.handle(command));

    verify(accounts, never()).save(any());
  }

  @Test
  void translatesALostRaceIntoTheSameDomainExceptionAsThePreCheck() {
    when(accounts.existsByEmail(email)).thenReturn(false);
    doThrow(new DataIntegrityViolationException("duplicate key")).when(accounts).save(any());
    RegisterPlatformAccountCommand command =
        new RegisterPlatformAccountCommand(email, VALID_PASSWORD);

    assertThatExceptionOfType(PlatformAccountEmailAlreadyRegisteredException.class)
        .isThrownBy(() -> service.handle(command));
  }
}
