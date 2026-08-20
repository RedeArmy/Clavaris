package com.clavaris.identity.application.usecase.authenticatewithpassword;

import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.AccountStatus;
import com.clavaris.identity.domain.model.PasswordCredential;

/**
 * Orchestration for {@link AuthenticateWithPasswordUseCase}. Every rejection path below throws the
 * exact same {@link InvalidCredentialsException}, with no distinguishing detail — unknown email, a
 * non-{@code ACTIVE} account, an account with no password credential (a social-only account
 * attempting a password login), and a genuinely wrong password are one outcome from the caller's
 * point of view, deliberately, to close the username-enumeration side channel a differentiated
 * response would open.
 */
public class AuthenticateWithPasswordService implements AuthenticateWithPasswordUseCase {

  private final AccountRepository accounts;
  private final PasswordVerifier verifier;

  public AuthenticateWithPasswordService(
      final AccountRepository accounts, final PasswordVerifier verifier) {
    this.accounts = accounts;
    this.verifier = verifier;
  }

  @Override
  public AccountId handle(final AuthenticateWithPasswordCommand command) {
    final Account account =
        accounts
            .findByOrganizationIdAndEmail(command.organizationId(), command.email())
            .orElseThrow(InvalidCredentialsException::new);

    // A suspended/deactivated account must never authenticate, even with the exactly-correct
    // password — checked before touching the password hash at all, not as an afterthought once a
    // credential match already succeeded.
    if (account.status() != AccountStatus.ACTIVE) {
      throw new InvalidCredentialsException();
    }

    final PasswordCredential credential =
        account.passwordCredential().orElseThrow(InvalidCredentialsException::new);

    if (!verifier.matches(command.rawPassword(), credential.passwordHash())) {
      throw new InvalidCredentialsException();
    }

    return account.id();
  }
}
