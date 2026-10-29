package com.clavaris.identity.application.usecase.authenticatewithidentifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.clavaris.identity.application.usecase.authenticatewithpassword.AuthenticateWithPasswordCommand;
import com.clavaris.identity.application.usecase.authenticatewithpassword.AuthenticateWithPasswordUseCase;
import com.clavaris.identity.application.usecase.authenticatewithpassword.EmailNotVerifiedException;
import com.clavaris.identity.application.usecase.authenticatewithpassword.InvalidCredentialsException;
import com.clavaris.identity.application.usecase.authenticatewithusername.AuthenticateWithUsernameCommand;
import com.clavaris.identity.application.usecase.authenticatewithusername.AuthenticateWithUsernameUseCase;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.Username;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** ADR-0032: which of the two sign-ins an identifier belongs to, decided by its shape alone. */
class AuthenticateWithIdentifierServiceTest {

  private static final String PASSWORD = "a-correct-password";

  private final OrganizationId organizationId = new OrganizationId(UUID.randomUUID());

  private AuthenticateWithPasswordUseCase byEmail;
  private AuthenticateWithUsernameUseCase byUsername;
  private AuthenticateWithIdentifierService service;
  private Account account;

  @BeforeEach
  void setUp() {
    byEmail = mock(AuthenticateWithPasswordUseCase.class);
    byUsername = mock(AuthenticateWithUsernameUseCase.class);
    service = new AuthenticateWithIdentifierService(byEmail, byUsername);
    account = Account.register(organizationId, new Email("ada@example.com"));
  }

  private AuthenticateWithIdentifierCommand command(final String identifier) {
    return new AuthenticateWithIdentifierCommand(organizationId, identifier, PASSWORD);
  }

  @Test
  void anIdentifierWithAnAtSignSignsInByEmail() {
    when(byEmail.handle(
            new AuthenticateWithPasswordCommand(
                organizationId, new Email("ada@example.com"), PASSWORD)))
        .thenReturn(account);

    assertThat(service.handle(command("ada@example.com"))).isSameAs(account);

    verifyNoInteractions(byUsername);
  }

  @Test
  void anIdentifierWithoutAnAtSignSignsInByUsername() {
    when(byUsername.handle(
            new AuthenticateWithUsernameCommand(organizationId, new Username("ada_l"), PASSWORD)))
        .thenReturn(account);

    assertThat(service.handle(command("ada_l"))).isSameAs(account);

    verifyNoInteractions(byEmail);
  }

  @Test
  void theIdentifierIsTrimmedAndTheCaseIsNormalisedByTheValueTypes() {
    when(byUsername.handle(
            new AuthenticateWithUsernameCommand(organizationId, new Username("ada_l"), PASSWORD)))
        .thenReturn(account);

    assertThat(service.handle(command("  ADA_L  "))).isSameAs(account);
  }

  @Test
  void aMalformedEmailIsTheSameRejectionAsAWrongPasswordAndNothingIsChecked() {
    final AuthenticateWithIdentifierCommand malformed = command("not an@email");

    assertThatExceptionOfType(InvalidCredentialsException.class)
        .isThrownBy(() -> service.handle(malformed));

    verifyNoInteractions(byEmail, byUsername);
  }

  @Test
  void aMalformedUsernameIsTheSameRejectionAsAWrongPassword() {
    final AuthenticateWithIdentifierCommand withSpace = command("has space");
    final AuthenticateWithIdentifierCommand tooShort = command("ab");
    final AuthenticateWithIdentifierCommand blank = command("   ");
    final AuthenticateWithIdentifierCommand absent =
        new AuthenticateWithIdentifierCommand(organizationId, null, PASSWORD);

    assertThatExceptionOfType(InvalidCredentialsException.class)
        .isThrownBy(() -> service.handle(withSpace));
    assertThatExceptionOfType(InvalidCredentialsException.class)
        .isThrownBy(() -> service.handle(tooShort));
    assertThatExceptionOfType(InvalidCredentialsException.class)
        .isThrownBy(() -> service.handle(blank));
    assertThatExceptionOfType(InvalidCredentialsException.class)
        .isThrownBy(() -> service.handle(absent));

    verifyNoInteractions(byEmail, byUsername);
  }

  // The deliberately distinguishable rejection must pass through untouched (ADR-0024 §2).
  @Test
  void anUnverifiedEmailFromEitherSignInIsNotCollapsedIntoAWrongPassword() {
    when(byEmail.handle(org.mockito.ArgumentMatchers.any()))
        .thenThrow(new EmailNotVerifiedException());
    when(byUsername.handle(org.mockito.ArgumentMatchers.any()))
        .thenThrow(new EmailNotVerifiedException());

    final AuthenticateWithIdentifierCommand byEmailCommand = command("ada@example.com");
    final AuthenticateWithIdentifierCommand byUsernameCommand = command("ada_l");

    assertThatExceptionOfType(EmailNotVerifiedException.class)
        .isThrownBy(() -> service.handle(byEmailCommand));
    assertThatExceptionOfType(EmailNotVerifiedException.class)
        .isThrownBy(() -> service.handle(byUsernameCommand));
  }

  @Test
  void anIllegalArgumentFromTheDelegatedSignInIsNotSwallowedAsABadPassword() {
    when(byEmail.handle(org.mockito.ArgumentMatchers.any()))
        .thenThrow(new IllegalArgumentException("a real bug, not a bad shape"));

    final AuthenticateWithIdentifierCommand command = command("ada@example.com");

    org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
        .isThrownBy(() -> service.handle(command));
    verify(byEmail).handle(org.mockito.ArgumentMatchers.any());
  }
}
