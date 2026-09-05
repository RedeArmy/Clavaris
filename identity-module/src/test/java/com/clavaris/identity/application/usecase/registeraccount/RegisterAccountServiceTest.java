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

import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicyProvider;
import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicySnapshot;
import com.clavaris.identity.application.usecase.requestemailverification.EmailVerificationMethod;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.Username;
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
  private AccountAuthenticationPolicyProvider policyProvider;
  private RegisterAccountService service;

  @BeforeEach
  void setUp() {
    accounts = mock(AccountRepository.class);
    hasher = mock(PasswordHasher.class);
    outbox = mock(EventOutboxWriter.class);
    policyProvider = mock(AccountAuthenticationPolicyProvider.class);
    // Matches today's real default (ADR-0024) — every existing test below predates this policy.
    when(policyProvider.policyFor(organizationId))
        .thenReturn(AccountAuthenticationPolicySnapshot.defaults());
    service = new RegisterAccountService(accounts, hasher, outbox, policyProvider);

    when(hasher.hash(anyString())).thenReturn("hashed-password");
  }

  @Test
  void registersAndReturnsANewAccountId() {
    when(accounts.existsByOrganizationIdAndEmail(organizationId, email)).thenReturn(false);

    AccountId id =
        service.handle(new RegisterAccountCommand(organizationId, email, VALID_PASSWORD, null));

    assertThat(id).isNotNull();
    verify(accounts).save(any());
  }

  @Test
  void hashesTheRawPasswordBeforeSaving_neverPersistsItRaw() {
    // BR-ID-01
    when(accounts.existsByOrganizationIdAndEmail(organizationId, email)).thenReturn(false);

    service.handle(new RegisterAccountCommand(organizationId, email, VALID_PASSWORD, null));

    verify(hasher).hash(VALID_PASSWORD);
  }

  @Test
  void writesExactlyOneAccountCreatedEventOnSuccess() {
    when(accounts.existsByOrganizationIdAndEmail(organizationId, email)).thenReturn(false);

    AccountId id =
        service.handle(new RegisterAccountCommand(organizationId, email, VALID_PASSWORD, null));

    verify(outbox).write(eq("account.created"), eq(id), any(), any());
  }

  @Test
  void rejectsAPasswordThatFailsPolicy_beforeTouchingTheRepository() {
    // Command construction pulled out of the lambda passed to isThrownBy: with it inside, the
    // lambda has two invocations that could throw (the constructor and handle()), leaving it
    // ambiguous which one static analysis — and a future reader — should credit for the
    // exception. A single invocation in the lambda keeps the assertion unambiguous.
    RegisterAccountCommand command =
        new RegisterAccountCommand(organizationId, email, "short", null);

    assertThatExceptionOfType(WeakPasswordException.class)
        .isThrownBy(() -> service.handle(command));

    verify(accounts, never()).save(any());
    verify(outbox, never()).write(any(), any(), any(), any());
  }

  @Test
  void rejectsRegistrationWhenThePreCheckFindsTheEmailAlreadyTaken() {
    when(accounts.existsByOrganizationIdAndEmail(organizationId, email)).thenReturn(true);
    RegisterAccountCommand command =
        new RegisterAccountCommand(organizationId, email, VALID_PASSWORD, null);

    assertThatExceptionOfType(EmailAlreadyRegisteredException.class)
        .isThrownBy(() -> service.handle(command));

    verify(accounts, never()).save(any());
    verify(outbox, never()).write(any(), any(), any(), any());
  }

  @Test
  void translatesALostRaceIntoTheSameDomainExceptionAsThePreCheck() {
    // The pre-check passes (no known conflict yet), but a concurrent request wins the actual
    // insert first — the unique constraint on accounts.(organization_id, email) is what's really
    // load-bearing here, not this pre-check (data-model.md §3).
    when(accounts.existsByOrganizationIdAndEmail(organizationId, email)).thenReturn(false);
    doThrow(new DataIntegrityViolationException("duplicate key")).when(accounts).save(any());
    RegisterAccountCommand command =
        new RegisterAccountCommand(organizationId, email, VALID_PASSWORD, null);

    assertThatExceptionOfType(EmailAlreadyRegisteredException.class)
        .isThrownBy(() -> service.handle(command));

    verify(outbox, never()).write(any(), any(), any(), any());
  }

  @Test
  void assignsTheSubmittedUsernameWhenTheOrganizationAllowsIt() {
    when(policyProvider.policyFor(organizationId)).thenReturn(usernameOptionalPolicy());
    when(accounts.existsByOrganizationIdAndEmail(organizationId, email)).thenReturn(false);
    when(accounts.existsByOrganizationIdAndUsername(eq(organizationId), any())).thenReturn(false);

    service.handle(new RegisterAccountCommand(organizationId, email, VALID_PASSWORD, "flowuser"));

    org.mockito.ArgumentCaptor<com.clavaris.identity.domain.model.Account> accountCaptor =
        org.mockito.ArgumentCaptor.forClass(com.clavaris.identity.domain.model.Account.class);
    verify(accounts).save(accountCaptor.capture());
    assertThat(accountCaptor.getValue().username()).contains(new Username("flowuser"));
  }

  @Test
  void rejectsRegistrationWhenTheOrganizationRequiresAUsernameAndNoneWasSubmitted() {
    when(policyProvider.policyFor(organizationId)).thenReturn(usernameRequiredPolicy());
    RegisterAccountCommand command =
        new RegisterAccountCommand(organizationId, email, VALID_PASSWORD, null);

    assertThatExceptionOfType(UsernameRequiredException.class)
        .isThrownBy(() -> service.handle(command));

    verify(accounts, never()).save(any());
  }

  @Test
  void rejectsRegistrationWhenThePreCheckFindsTheUsernameAlreadyTaken() {
    when(policyProvider.policyFor(organizationId)).thenReturn(usernameOptionalPolicy());
    when(accounts.existsByOrganizationIdAndEmail(organizationId, email)).thenReturn(false);
    when(accounts.existsByOrganizationIdAndUsername(eq(organizationId), any())).thenReturn(true);
    RegisterAccountCommand command =
        new RegisterAccountCommand(organizationId, email, VALID_PASSWORD, "taken");

    assertThatExceptionOfType(UsernameAlreadyRegisteredException.class)
        .isThrownBy(() -> service.handle(command));

    verify(accounts, never()).save(any());
  }

  @Test
  void attachesARandomGeneratedPasswordWhenPasswordAtSignUpIsDisabledAndNoneWasSubmitted() {
    when(policyProvider.policyFor(organizationId)).thenReturn(passwordOptionalPolicy());
    when(accounts.existsByOrganizationIdAndEmail(organizationId, email)).thenReturn(false);

    service.handle(new RegisterAccountCommand(organizationId, email, null, null));

    // ADR-0024 §5: never the raw submitted value (there wasn't one) — a real, hashed credential
    // still gets attached, just never the literal null/blank the caller sent.
    verify(hasher).hash(argThat(raw -> raw != null && raw.length() == 32));
    verify(accounts).save(any());
  }

  @Test
  void rejectsMissingPasswordWhenTheOrganizationStillRequiresOneAtSignUp() {
    RegisterAccountCommand command = new RegisterAccountCommand(organizationId, email, null, null);

    assertThatExceptionOfType(WeakPasswordException.class)
        .isThrownBy(() -> service.handle(command));

    verify(accounts, never()).save(any());
  }

  private static AccountAuthenticationPolicySnapshot usernameOptionalPolicy() {
    return new AccountAuthenticationPolicySnapshot(
        false, EmailVerificationMethod.LINK, false, false, true, false, false, true, false);
  }

  private static AccountAuthenticationPolicySnapshot usernameRequiredPolicy() {
    return new AccountAuthenticationPolicySnapshot(
        false, EmailVerificationMethod.LINK, false, false, true, true, false, true, false);
  }

  private static AccountAuthenticationPolicySnapshot passwordOptionalPolicy() {
    return new AccountAuthenticationPolicySnapshot(
        false, EmailVerificationMethod.LINK, true, false, false, false, false, false, false);
  }

  private static String argThat(final java.util.function.Predicate<String> predicate) {
    return org.mockito.ArgumentMatchers.argThat(predicate::test);
  }
}
