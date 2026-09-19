package com.clavaris.identity.application.usecase.admincreateaccountfororganization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.identity.application.usecase.registeraccount.AccessRestrictedException;
import com.clavaris.identity.application.usecase.registeraccount.AccessRestrictionPolicyProvider;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.registeraccount.EmailAlreadyRegisteredException;
import com.clavaris.identity.application.usecase.registeraccount.PasswordHasher;
import com.clavaris.identity.application.usecase.registeraccount.UsernameAlreadyRegisteredException;
import com.clavaris.identity.application.usecase.registeraccount.WeakPasswordException;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.Username;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AdminCreateAccountForOrganizationServiceTest {

  private static final String VALID_PASSWORD = "a-valid-password";

  private final OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
  private final Email email = new Email("new-user@example.com");

  private AccountRepository accounts;
  private PasswordHasher hasher;
  private AccessRestrictionPolicyProvider accessRestrictions;
  private AdminCreateAccountForOrganizationService service;

  @BeforeEach
  void setUp() {
    accounts = mock(AccountRepository.class);
    hasher = mock(PasswordHasher.class);
    accessRestrictions = mock(AccessRestrictionPolicyProvider.class);
    when(accessRestrictions.isAllowed(any(), any())).thenReturn(true);
    when(hasher.hash(anyString())).thenReturn("hashed-password");
    service = new AdminCreateAccountForOrganizationService(accounts, hasher, accessRestrictions);
  }

  private AdminCreateAccountForOrganizationCommand command() {
    return new AdminCreateAccountForOrganizationCommand(
        organizationId, email, VALID_PASSWORD, null, null, null, null, false, false);
  }

  @Test
  void createsAndReturnsANewAccountIdWithThePasswordSetDirectly() {
    AccountId id = service.handle(command());

    assertThat(id).isNotNull();
    ArgumentCaptor<Account> saved = ArgumentCaptor.forClass(Account.class);
    verify(accounts).insert(saved.capture());
    assertThat(saved.getValue().passwordCredential()).isPresent();
    verify(hasher).hash(VALID_PASSWORD);
  }

  @Test
  void marksTheEmailVerifiedImmediatelyUnlikeSelfServiceRegistration() {
    service.handle(command());

    ArgumentCaptor<Account> saved = ArgumentCaptor.forClass(Account.class);
    verify(accounts).insert(saved.capture());
    assertThat(saved.getValue().emailVerifiedAt()).isPresent();
  }

  @Test
  void carriesOptionalProfileFieldsThrough() {
    AdminCreateAccountForOrganizationCommand command =
        new AdminCreateAccountForOrganizationCommand(
            organizationId,
            email,
            VALID_PASSWORD,
            "Ada",
            "Lovelace",
            null,
            "+15550001111",
            false,
            false);

    service.handle(command);

    ArgumentCaptor<Account> saved = ArgumentCaptor.forClass(Account.class);
    verify(accounts).insert(saved.capture());
    assertThat(saved.getValue().firstName()).contains("Ada");
    assertThat(saved.getValue().lastName()).contains("Lovelace");
    assertThat(saved.getValue().phoneNumber()).contains("+15550001111");
  }

  @Test
  void assignsTheUsernameWhenSubmitted() {
    Username username = new Username("adalovelace");
    when(accounts.existsByOrganizationIdAndUsername(organizationId, username)).thenReturn(false);
    AdminCreateAccountForOrganizationCommand command =
        new AdminCreateAccountForOrganizationCommand(
            organizationId, email, VALID_PASSWORD, null, null, "adalovelace", null, false, false);

    service.handle(command);

    ArgumentCaptor<Account> saved = ArgumentCaptor.forClass(Account.class);
    verify(accounts).insert(saved.capture());
    assertThat(saved.getValue().username()).contains(username);
  }

  @Test
  void rejectsAnAlreadyTakenUsername() {
    Username username = new Username("adalovelace");
    when(accounts.existsByOrganizationIdAndUsername(organizationId, username)).thenReturn(true);
    AdminCreateAccountForOrganizationCommand command =
        new AdminCreateAccountForOrganizationCommand(
            organizationId, email, VALID_PASSWORD, null, null, "adalovelace", null, false, false);

    assertThatExceptionOfType(UsernameAlreadyRegisteredException.class)
        .isThrownBy(() -> service.handle(command));

    verify(accounts, never()).insert(any());
  }

  @Test
  void rejectsAnAlreadyRegisteredEmail() {
    when(accounts.existsByOrganizationIdAndEmail(organizationId, email)).thenReturn(true);
    AdminCreateAccountForOrganizationCommand command = command();

    assertThatExceptionOfType(EmailAlreadyRegisteredException.class)
        .isThrownBy(() -> service.handle(command));

    verify(accounts, never()).insert(any());
  }

  @Test
  void rejectsRegistrationWhenTheAccessRestrictionPolicyDisallowsTheEmailByDefault() {
    when(accessRestrictions.isAllowed(organizationId, email)).thenReturn(false);
    AdminCreateAccountForOrganizationCommand command = command();

    assertThatExceptionOfType(AccessRestrictedException.class)
        .isThrownBy(() -> service.handle(command));

    verify(accounts, never()).insert(any());
  }

  @Test
  void ignoreAccessRestrictionsSkipsThatCheckEntirely() {
    when(accessRestrictions.isAllowed(organizationId, email)).thenReturn(false);
    AdminCreateAccountForOrganizationCommand command =
        new AdminCreateAccountForOrganizationCommand(
            organizationId, email, VALID_PASSWORD, null, null, null, null, false, true);

    service.handle(command);

    verify(accounts).insert(any());
  }

  @Test
  void rejectsAWeakPasswordByDefault() {
    AdminCreateAccountForOrganizationCommand command =
        new AdminCreateAccountForOrganizationCommand(
            organizationId, email, "short", null, null, null, null, false, false);

    assertThatExceptionOfType(WeakPasswordException.class)
        .isThrownBy(() -> service.handle(command));

    verify(accounts, never()).insert(any());
  }

  @Test
  void ignorePasswordPolicySkipsTheStrengthCheck() {
    AdminCreateAccountForOrganizationCommand command =
        new AdminCreateAccountForOrganizationCommand(
            organizationId, email, "short", null, null, null, null, true, false);

    service.handle(command);

    verify(accounts).insert(any());
  }
}
