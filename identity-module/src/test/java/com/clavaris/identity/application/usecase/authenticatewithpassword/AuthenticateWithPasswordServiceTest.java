package com.clavaris.identity.application.usecase.authenticatewithpassword;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.AccountStatus;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuthenticateWithPasswordServiceTest {

  private static final String RAW_PASSWORD = "a-correct-password";

  private final OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
  private final Email email = new Email("user@example.com");

  private AccountRepository accounts;
  private PasswordVerifier verifier;
  private AuthenticateWithPasswordService service;

  @BeforeEach
  void setUp() {
    accounts = mock(AccountRepository.class);
    verifier = mock(PasswordVerifier.class);
    service = new AuthenticateWithPasswordService(accounts, verifier);
  }

  @Test
  void returnsTheAccountIdWhenTheEmailPasswordAndOrganizationAllMatch() {
    Account account = Account.register(organizationId, email);
    account.attachPasswordCredential("argon2id$stored-hash");
    when(accounts.findByOrganizationIdAndEmail(organizationId, email))
        .thenReturn(Optional.of(account));
    when(verifier.matches(RAW_PASSWORD, "argon2id$stored-hash")).thenReturn(true);

    AccountId result =
        service.handle(new AuthenticateWithPasswordCommand(organizationId, email, RAW_PASSWORD));

    assertThat(result).isEqualTo(account.id());
  }

  @Test
  void rejectsAnUnknownEmail() {
    when(accounts.findByOrganizationIdAndEmail(organizationId, email)).thenReturn(Optional.empty());
    AuthenticateWithPasswordCommand command =
        new AuthenticateWithPasswordCommand(organizationId, email, RAW_PASSWORD);

    assertThatExceptionOfType(InvalidCredentialsException.class)
        .isThrownBy(() -> service.handle(command));

    verify(verifier, never()).matches(any(), any());
  }

  @Test
  void rejectsAWrongPassword() {
    Account account = Account.register(organizationId, email);
    account.attachPasswordCredential("argon2id$stored-hash");
    when(accounts.findByOrganizationIdAndEmail(organizationId, email))
        .thenReturn(Optional.of(account));
    when(verifier.matches(RAW_PASSWORD, "argon2id$stored-hash")).thenReturn(false);
    AuthenticateWithPasswordCommand command =
        new AuthenticateWithPasswordCommand(organizationId, email, RAW_PASSWORD);

    assertThatExceptionOfType(InvalidCredentialsException.class)
        .isThrownBy(() -> service.handle(command));
  }

  @Test
  void rejectsASuspendedAccountEvenWithTheCorrectPassword() {
    Account account =
        Account.reconstitute(
            new AccountId(UUID.randomUUID()),
            organizationId,
            email,
            Instant.now(),
            null,
            AccountStatus.SUSPENDED,
            null);
    when(accounts.findByOrganizationIdAndEmail(organizationId, email))
        .thenReturn(Optional.of(account));
    AuthenticateWithPasswordCommand command =
        new AuthenticateWithPasswordCommand(organizationId, email, RAW_PASSWORD);

    assertThatExceptionOfType(InvalidCredentialsException.class)
        .isThrownBy(() -> service.handle(command));

    // A suspended account must never even reach the password check — the account-status guard
    // runs first, deliberately, not as an afterthought once a credential match already succeeded.
    verify(verifier, never()).matches(any(), any());
  }

  @Test
  void rejectsAnAccountWithNoPasswordCredentialAttached() {
    // A social-only account (once SocialIdentity exists) attempting a password login — BR-ID-02
    // guarantees at least one auth method exists, just not necessarily this one.
    Account account =
        Account.reconstitute(
            new AccountId(UUID.randomUUID()),
            organizationId,
            email,
            Instant.now(),
            null,
            AccountStatus.ACTIVE,
            null);
    when(accounts.findByOrganizationIdAndEmail(organizationId, email))
        .thenReturn(Optional.of(account));
    AuthenticateWithPasswordCommand command =
        new AuthenticateWithPasswordCommand(organizationId, email, RAW_PASSWORD);

    assertThatExceptionOfType(InvalidCredentialsException.class)
        .isThrownBy(() -> service.handle(command));
  }
}
