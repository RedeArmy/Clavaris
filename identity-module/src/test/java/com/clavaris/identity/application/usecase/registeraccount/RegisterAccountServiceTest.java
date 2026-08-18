package com.clavaris.identity.application.usecase.registeraccount;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class RegisterAccountServiceTest {

  private static final String VALID_PASSWORD = "a-valid-password";

  private final OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
  private final Email email = new Email("new-user@example.com");

  private AccountRepository accounts;
  private PasswordHasher hasher;
  private EventOutboxWriter outbox;
  private RegisterAccountService service;

  @BeforeEach
  void setUp() {
    accounts = mock(AccountRepository.class);
    hasher = mock(PasswordHasher.class);
    outbox = mock(EventOutboxWriter.class);
    service = new RegisterAccountService(accounts, hasher, outbox);

    when(hasher.hash(anyString())).thenReturn("hashed-password");
  }

  @Test
  void registersAndReturnsANewAccountId() {
    when(accounts.existsByOrganizationIdAndEmail(organizationId, email)).thenReturn(false);

    AccountId id =
        service.handle(new RegisterAccountCommand(organizationId, email, VALID_PASSWORD));

    assertThat(id).isNotNull();
    verify(accounts).save(any());
  }

  @Test
  void hashesTheRawPasswordBeforeSaving_neverPersistsItRaw() {
    // BR-ID-01
    when(accounts.existsByOrganizationIdAndEmail(organizationId, email)).thenReturn(false);

    service.handle(new RegisterAccountCommand(organizationId, email, VALID_PASSWORD));

    verify(hasher).hash(VALID_PASSWORD);
  }

  @Test
  void writesExactlyOneAccountCreatedEventOnSuccess() {
    when(accounts.existsByOrganizationIdAndEmail(organizationId, email)).thenReturn(false);

    AccountId id =
        service.handle(new RegisterAccountCommand(organizationId, email, VALID_PASSWORD));

    verify(outbox).write(eq("account.created"), eq(id), any());
  }

  @Test
  void rejectsAPasswordThatFailsPolicy_beforeTouchingTheRepository() {
    assertThatExceptionOfType(WeakPasswordException.class)
        .isThrownBy(
            () -> service.handle(new RegisterAccountCommand(organizationId, email, "short")));

    verify(accounts, never()).save(any());
    verify(outbox, never()).write(any(), any(), any());
  }

  @Test
  void rejectsRegistrationWhenThePreCheckFindsTheEmailAlreadyTaken() {
    when(accounts.existsByOrganizationIdAndEmail(organizationId, email)).thenReturn(true);

    assertThatExceptionOfType(EmailAlreadyRegisteredException.class)
        .isThrownBy(
            () ->
                service.handle(new RegisterAccountCommand(organizationId, email, VALID_PASSWORD)));

    verify(accounts, never()).save(any());
    verify(outbox, never()).write(any(), any(), any());
  }

  @Test
  void translatesALostRaceIntoTheSameDomainExceptionAsThePreCheck() {
    // The pre-check passes (no known conflict yet), but a concurrent request wins the actual
    // insert first — the unique constraint on accounts.(organization_id, email) is what's really
    // load-bearing here, not this pre-check (data-model.md §3).
    when(accounts.existsByOrganizationIdAndEmail(organizationId, email)).thenReturn(false);
    doThrow(new DataIntegrityViolationException("duplicate key")).when(accounts).save(any());

    assertThatExceptionOfType(EmailAlreadyRegisteredException.class)
        .isThrownBy(
            () ->
                service.handle(new RegisterAccountCommand(organizationId, email, VALID_PASSWORD)));

    verify(outbox, never()).write(any(), any(), any());
  }
}
