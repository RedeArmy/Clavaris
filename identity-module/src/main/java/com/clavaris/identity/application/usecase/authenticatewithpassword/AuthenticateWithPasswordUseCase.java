package com.clavaris.identity.application.usecase.authenticatewithpassword;

import com.clavaris.identity.domain.model.Account;

/**
 * BR-ORG-02: the hosted login screen for a given {@code OAuthClient} authenticates only against
 * that client's own Organization's account pool — {@link AuthenticateWithPasswordCommand} carries
 * {@code organizationId} for exactly that reason, never inferred from anywhere else.
 */
@FunctionalInterface
public interface AuthenticateWithPasswordUseCase {

  /**
   * TD-PERF-015: returns the full {@link Account}, not just its id — the caller ({@code
   * LoginController}) already needs it for {@code SessionTaskGate}/{@code
   * AuthenticatedSessionCompletion} moments later in the same request; returning the id alone
   * forced both to redundantly re-fetch the exact row this method had already loaded.
   *
   * @throws InvalidCredentialsException on any failure — see its own Javadoc for why every failure
   *     mode is indistinguishable from the caller's point of view.
   */
  Account handle(AuthenticateWithPasswordCommand command);
}
