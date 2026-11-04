package com.clavaris.identity.application.usecase.registerplatformaccount;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.identity.application.usecase.registeraccount.BreachedPasswordChecker;
import com.clavaris.identity.application.usecase.registeraccount.BreachedPasswordException;
import com.clavaris.identity.application.usecase.registeraccount.PasswordHasher;
import com.clavaris.identity.application.usecase.registeraccount.WeakPasswordException;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.PlatformAccountId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionOperations;

class RegisterPlatformAccountServiceTest {

  private static final String VALID_PASSWORD = "a-valid-password";

  private final Email email = new Email("founder@example.com");

  private PlatformAccountRepository accounts;
  private PasswordHasher hasher;
  private BreachedPasswordChecker breachedPasswordChecker;
  private RegisterPlatformAccountService service;

  @BeforeEach
  void setUp() {
    accounts = mock(PlatformAccountRepository.class);
    hasher = mock(PasswordHasher.class);
    breachedPasswordChecker = mock(BreachedPasswordChecker.class);
    // BR-ID-07: matches today's real default (no corpus match) — every existing test below
    // predates this check.
    when(breachedPasswordChecker.isBreached(any())).thenReturn(false);
    service =
        new RegisterPlatformAccountService(
            accounts, hasher, breachedPasswordChecker, TransactionOperations.withoutTransaction());

    when(hasher.hash(anyString())).thenReturn("hashed-password");
  }

  @Test
  void registersAndReturnsANewPlatformAccountId() {
    when(accounts.existsByEmail(email)).thenReturn(false);

    PlatformAccountId id =
        service.handle(new RegisterPlatformAccountCommand(email, VALID_PASSWORD));

    assertThat(id).isNotNull();
    verify(accounts).insert(any());
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

    verify(accounts, never()).insert(any());
  }

  @Test
  void rejectsAPasswordTheCorpusReportsAsBreached_beforeTouchingTheRepository() {
    // BR-ID-07
    when(breachedPasswordChecker.isBreached(VALID_PASSWORD)).thenReturn(true);
    RegisterPlatformAccountCommand command =
        new RegisterPlatformAccountCommand(email, VALID_PASSWORD);

    assertThatExceptionOfType(BreachedPasswordException.class)
        .isThrownBy(() -> service.handle(command));

    verify(accounts, never()).insert(any());
  }

  @Test
  void rejectsRegistrationWhenThePreCheckFindsTheEmailAlreadyTaken() {
    when(accounts.existsByEmail(email)).thenReturn(true);
    RegisterPlatformAccountCommand command =
        new RegisterPlatformAccountCommand(email, VALID_PASSWORD);

    assertThatExceptionOfType(PlatformAccountEmailAlreadyRegisteredException.class)
        .isThrownBy(() -> service.handle(command));

    verify(accounts, never()).insert(any());
  }

  @Test
  void translatesALostRaceIntoTheSameDomainExceptionAsThePreCheck() {
    when(accounts.existsByEmail(email)).thenReturn(false);
    doThrow(new DataIntegrityViolationException("duplicate key")).when(accounts).insert(any());
    RegisterPlatformAccountCommand command =
        new RegisterPlatformAccountCommand(email, VALID_PASSWORD);

    assertThatExceptionOfType(PlatformAccountEmailAlreadyRegisteredException.class)
        .isThrownBy(() -> service.handle(command));
  }

  // TD-PERF-053: the hash waits on a bulkhead and costs tens of milliseconds, and the pool is ten
  // connections, so it runs before any transaction opens; only the insert runs inside one.
  @Test
  void theHashIsComputedOutsideTheTransactionAndOnlyTheInsertInsideIt() {
    java.util.concurrent.atomic.AtomicBoolean inTransaction =
        new java.util.concurrent.atomic.AtomicBoolean();
    java.util.concurrent.atomic.AtomicBoolean hashedInside =
        new java.util.concurrent.atomic.AtomicBoolean();
    java.util.concurrent.atomic.AtomicBoolean insertedInside =
        new java.util.concurrent.atomic.AtomicBoolean();
    TransactionOperations recording =
        new TransactionOperations() {
          @Override
          public <T> T execute(
              final org.springframework.transaction.support.TransactionCallback<T> action) {
            inTransaction.set(true);
            try {
              return action.doInTransaction(
                  new org.springframework.transaction.support.SimpleTransactionStatus());
            } finally {
              inTransaction.set(false);
            }
          }
        };
    service =
        new RegisterPlatformAccountService(accounts, hasher, breachedPasswordChecker, recording);
    when(accounts.existsByEmail(email)).thenReturn(false);
    when(hasher.hash(anyString()))
        .thenAnswer(
            invocation -> {
              hashedInside.set(inTransaction.get());
              return "hashed-password";
            });
    doAnswer(
            invocation -> {
              insertedInside.set(inTransaction.get());
              return null;
            })
        .when(accounts)
        .insert(any());

    service.handle(new RegisterPlatformAccountCommand(email, VALID_PASSWORD));

    assertThat(hashedInside).as("hashing happened outside the transaction").isFalse();
    assertThat(insertedInside).as("the insert happened inside it").isTrue();
  }
}
