package com.clavaris.identity.application.usecase.authenticatewithpassword;

import com.clavaris.identity.domain.model.AccountId;

/**
 * BR-ORG-02: the hosted login screen for a given {@code OAuthClient} authenticates only against
 * that client's own Organization's account pool — {@link AuthenticateWithPasswordCommand} carries
 * {@code organizationId} for exactly that reason, never inferred from anywhere else.
 */
@FunctionalInterface
public interface AuthenticateWithPasswordUseCase {

  /**
   * @return the authenticated {@link AccountId} on success.
   * @throws InvalidCredentialsException on any failure — see its own Javadoc for why every failure
   *     mode is indistinguishable from the caller's point of view.
   */
  AccountId handle(AuthenticateWithPasswordCommand command);
}
