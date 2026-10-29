package com.clavaris.identity.application.usecase.authenticatewithidentifier;

import com.clavaris.identity.application.usecase.authenticatewithpassword.EmailNotVerifiedException;
import com.clavaris.identity.application.usecase.authenticatewithpassword.InvalidCredentialsException;
import com.clavaris.identity.application.usecase.authenticatewithpassword.VerificationOverloadedException;
import com.clavaris.identity.domain.model.Account;

/**
 * ADR-0032: the hosted sign-in's single "email or username" field. An identifier with an {@code @}
 * is an email address and signs in exactly as {@code AuthenticateWithPasswordUseCase} does; one
 * without is a username and signs in as {@code AuthenticateWithUsernameUseCase} does, which only
 * the Organizations with {@code usernameSignInEnabled} accept. Both share the same password
 * verification, so the factor is still {@code pwd}.
 */
@FunctionalInterface
public interface AuthenticateWithIdentifierUseCase {

  /**
   * @throws InvalidCredentialsException on any failure, including an identifier of neither shape —
   *     see its own Javadoc for why every failure is indistinguishable to the caller
   * @throws EmailNotVerifiedException the one deliberately distinguishable rejection (ADR-0024 §2)
   * @throws VerificationOverloadedException the password check could not run (TD-FUT-017)
   */
  Account handle(AuthenticateWithIdentifierCommand command);
}
