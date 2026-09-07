package com.clavaris.identity.application.usecase.authenticatewithemaillink;

import com.clavaris.identity.domain.model.Account;

/**
 * TD-PERF-015: returns the full {@link Account}, not just its id — see {@code
 * AuthenticateWithPasswordUseCase}'s own identical Javadoc for why.
 */
@FunctionalInterface
public interface AuthenticateWithEmailLinkUseCase {

  Account handle(AuthenticateWithEmailLinkCommand command);
}
