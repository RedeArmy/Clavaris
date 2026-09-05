package com.clavaris.identity.application.usecase.authenticatewithusername;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clavaris.identity.application.usecase.authenticatewithpassword.EmailNotVerifiedException;
import com.clavaris.identity.application.usecase.authenticatewithpassword.InvalidCredentialsException;
import com.clavaris.identity.application.usecase.authenticatewithpassword.PasswordVerifier;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicyProvider;
import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicySnapshot;
import com.clavaris.identity.application.usecase.requestemailverification.EmailVerificationMethod;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.AccountStatus;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.Username;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuthenticateWithUsernameServiceTest {

  private static final String RAW_PASSWORD = "a-correct-password";

  private final OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
  private final Username username = new Username("some_user");

  private AccountRepository accounts;
  private PasswordVerifier verifier;
  private AccountAuthenticationPolicyProvider policyProvider;
  private AuthenticateWithUsernameService service;

  @BeforeEach
  void setUp() {
    accounts = mock(AccountRepository.class);
    verifier = mock(PasswordVerifier.class);
    policyProvider = mock(AccountAuthenticationPolicyProvider.class);
    when(policyProvider.policyFor(organizationId))
        .thenReturn(AccountAuthenticationPolicySnapshot.defaults());
    service = new AuthenticateWithUsernameService(accounts, verifier, policyProvider);
  }

  private Account accountWithUsername() {
    Account account = Account.register(organizationId, new Email("user@example.com"));
    account.attachPasswordCredential("argon2id$stored-hash");
    account.assignUsername(username);
    return account;
  }

  @Test
  void returnsTheAccountIdWhenTheUsernameAndPasswordMatch() {
    Account account = accountWithUsername();
    when(accounts.findByOrganizationIdAndUsername(organizationId, username))
        .thenReturn(Optional.of(account));
    when(verifier.matches(RAW_PASSWORD, "argon2id$stored-hash")).thenReturn(true);

    AccountId result =
        service.handle(new AuthenticateWithUsernameCommand(organizationId, username, RAW_PASSWORD));

    assertThat(result).isEqualTo(account.id());
  }

  @Test
  void rejectsAnUnknownUsername() {
    when(accounts.findByOrganizationIdAndUsername(organizationId, username))
        .thenReturn(Optional.empty());
    AuthenticateWithUsernameCommand command =
        new AuthenticateWithUsernameCommand(organizationId, username, RAW_PASSWORD);

    assertThatExceptionOfType(InvalidCredentialsException.class)
        .isThrownBy(() -> service.handle(command));
  }

  @Test
  void rejectsAWrongPassword() {
    Account account = accountWithUsername();
    when(accounts.findByOrganizationIdAndUsername(organizationId, username))
        .thenReturn(Optional.of(account));
    when(verifier.matches(RAW_PASSWORD, "argon2id$stored-hash")).thenReturn(false);
    AuthenticateWithUsernameCommand command =
        new AuthenticateWithUsernameCommand(organizationId, username, RAW_PASSWORD);

    assertThatExceptionOfType(InvalidCredentialsException.class)
        .isThrownBy(() -> service.handle(command));
  }

  @Test
  void rejectsASuspendedAccount() {
    Account account =
        Account.reconstitute(
            new AccountId(UUID.randomUUID()),
            organizationId,
            new Email("user@example.com"),
            Instant.now(),
            null,
            AccountStatus.SUSPENDED,
            null,
            username,
            null);
    when(accounts.findByOrganizationIdAndUsername(organizationId, username))
        .thenReturn(Optional.of(account));
    AuthenticateWithUsernameCommand command =
        new AuthenticateWithUsernameCommand(organizationId, username, RAW_PASSWORD);

    assertThatExceptionOfType(InvalidCredentialsException.class)
        .isThrownBy(() -> service.handle(command));
  }

  @Test
  void rejectsAnUnverifiedEmailWhenThePolicyRequiresVerificationAtSignIn() {
    Account account = accountWithUsername();
    when(accounts.findByOrganizationIdAndUsername(organizationId, username))
        .thenReturn(Optional.of(account));
    when(verifier.matches(RAW_PASSWORD, "argon2id$stored-hash")).thenReturn(true);
    when(policyProvider.policyFor(organizationId))
        .thenReturn(
            new AccountAuthenticationPolicySnapshot(
                true,
                EmailVerificationMethod.LINK,
                false,
                false,
                false,
                false,
                false,
                true,
                false));
    AuthenticateWithUsernameCommand command =
        new AuthenticateWithUsernameCommand(organizationId, username, RAW_PASSWORD);

    assertThatExceptionOfType(EmailNotVerifiedException.class)
        .isThrownBy(() -> service.handle(command));
  }
}
