package com.clavaris.identity.application.usecase.authenticatewithidentifier;

import com.clavaris.identity.application.usecase.authenticatewithpassword.AuthenticateWithPasswordCommand;
import com.clavaris.identity.application.usecase.authenticatewithpassword.AuthenticateWithPasswordUseCase;
import com.clavaris.identity.application.usecase.authenticatewithpassword.InvalidCredentialsException;
import com.clavaris.identity.application.usecase.authenticatewithusername.AuthenticateWithUsernameCommand;
import com.clavaris.identity.application.usecase.authenticatewithusername.AuthenticateWithUsernameUseCase;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.Username;

/**
 * Orchestration for {@link AuthenticateWithIdentifierUseCase}. It only decides which of the two
 * existing sign-ins the identifier belongs to, by its shape alone: {@link Username} cannot contain
 * an {@code @} and every {@link Email} has one, so the choice never depends on what exists in the
 * database and leaks nothing about it. Everything that matters (the anti-enumeration collapsing,
 * the timing equalisation of BR-ID-22, the email-verification rule, the {@code
 * usernameSignInEnabled} gate) stays in the two services it delegates to.
 *
 * <p>An identifier that is neither a valid email nor a valid username is rejected with the same
 * {@link InvalidCredentialsException} as a wrong password. No password check runs for it, which
 * leaks nothing: its shape is the caller's own input, not a fact about any account.
 */
public class AuthenticateWithIdentifierService implements AuthenticateWithIdentifierUseCase {

  private static final char EMAIL_MARKER = '@';

  private final AuthenticateWithPasswordUseCase byEmail;
  private final AuthenticateWithUsernameUseCase byUsername;

  public AuthenticateWithIdentifierService(
      final AuthenticateWithPasswordUseCase byEmail,
      final AuthenticateWithUsernameUseCase byUsername) {
    this.byEmail = byEmail;
    this.byUsername = byUsername;
  }

  @Override
  public Account handle(final AuthenticateWithIdentifierCommand command) {
    final String identifier =
        command.rawIdentifier() == null ? "" : command.rawIdentifier().strip();
    if (identifier.indexOf(EMAIL_MARKER) >= 0) {
      return byEmail.handle(
          new AuthenticateWithPasswordCommand(
              command.organizationId(), emailOrReject(identifier), command.rawPassword()));
    }
    return byUsername.handle(
        new AuthenticateWithUsernameCommand(
            command.organizationId(), usernameOrReject(identifier), command.rawPassword()));
  }

  // Email's and Username's own constructors reject a bad shape; only that is translated, so an
  // IllegalArgumentException from the delegated sign-in itself is never swallowed as a bad
  // password.
  private static Email emailOrReject(final String identifier) {
    try {
      return new Email(identifier);
    } catch (final IllegalArgumentException _) {
      throw new InvalidCredentialsException();
    }
  }

  private static Username usernameOrReject(final String identifier) {
    try {
      return new Username(identifier);
    } catch (final IllegalArgumentException _) {
      throw new InvalidCredentialsException();
    }
  }
}
