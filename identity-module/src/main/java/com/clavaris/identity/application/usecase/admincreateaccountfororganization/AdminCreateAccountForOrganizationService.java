package com.clavaris.identity.application.usecase.admincreateaccountfororganization;

import com.clavaris.identity.application.usecase.registeraccount.AccessRestrictedException;
import com.clavaris.identity.application.usecase.registeraccount.AccessRestrictionPolicyProvider;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.registeraccount.EmailAlreadyRegisteredException;
import com.clavaris.identity.application.usecase.registeraccount.PasswordHasher;
import com.clavaris.identity.application.usecase.registeraccount.UsernameAlreadyRegisteredException;
import com.clavaris.identity.application.usecase.registeraccount.WeakPasswordException;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.Username;
import com.clavaris.identity.domain.service.PasswordPolicy;

/**
 * Orchestration for {@link AdminCreateAccountForOrganizationUseCase} — the dashboard "Users" tab's
 * "Create user" modal (Clerk parity). Deliberately a separate use case from {@code
 * RegisterAccountService}, not an optional-parameter extension of it: the two differ in ways that
 * would otherwise force every self-service registration call site to reason about admin-only
 * branches that can never apply to it —
 *
 * <ul>
 *   <li>the password is never auto-generated and never optional; the operator sets it directly.
 *   <li>the email is marked verified immediately (an operator creating the account is vouching for
 *       it, the same trust Clerk's own dashboard places in this exact action) — no welcome/
 *       verification email, unlike {@code RegisterAccountService}'s own always-sends-one posture.
 *   <li>{@code ignorePasswordPolicy}/{@code ignoreAccessRestrictions} bypass checks {@code
 *       RegisterAccountService} never lets a caller skip at all.
 * </ul>
 *
 * Reuses {@code registeraccount}'s own exception types ({@link EmailAlreadyRegisteredException},
 * {@link UsernameAlreadyRegisteredException}, {@link WeakPasswordException}, {@link
 * AccessRestrictedException}) rather than minting parallel ones — they name the same domain
 * concepts here as there, and {@code AdminAccountsController} can reuse the exact same
 * exception-to-HTTP-response mapping {@code RegisterAccountController} already established.
 */
// PMD.LongVariable: accessRestrictions names exactly what it is. PMD.CyclomaticComplexity:
// handle()'s four early-rejection branches (access restriction, email conflict, weak password,
// username conflict) are each a genuinely distinct precondition, same posture
// RegisterAccountService's own identical suppression already documents. PMD.OnlyOneReturn:
// validateUsername's "no username submitted"/"resolved" exits are two independent, equally valid
// outcomes, same rationale as every other early-return chain in this codebase.
@SuppressWarnings({"PMD.LongVariable", "PMD.CyclomaticComplexity", "PMD.OnlyOneReturn"})
public class AdminCreateAccountForOrganizationService
    implements AdminCreateAccountForOrganizationUseCase {

  private final AccountRepository accounts;
  private final PasswordHasher hasher;
  private final AccessRestrictionPolicyProvider accessRestrictions;

  public AdminCreateAccountForOrganizationService(
      final AccountRepository accounts,
      final PasswordHasher hasher,
      final AccessRestrictionPolicyProvider accessRestrictions) {
    this.accounts = accounts;
    this.hasher = hasher;
    this.accessRestrictions = accessRestrictions;
  }

  @Override
  public AccountId handle(final AdminCreateAccountForOrganizationCommand command) {
    if (!command.ignoreAccessRestrictions()
        && !accessRestrictions.isAllowed(command.organizationId(), command.email())) {
      throw new AccessRestrictedException();
    }
    if (accounts.existsByOrganizationIdAndEmail(command.organizationId(), command.email())) {
      throw new EmailAlreadyRegisteredException(command.organizationId());
    }
    if (!command.ignorePasswordPolicy() && !PasswordPolicy.isSatisfiedBy(command.rawPassword())) {
      throw new WeakPasswordException();
    }

    final Username username = validateUsername(command);

    final Account account =
        Account.register(
            command.organizationId(),
            command.email(),
            blankToNull(command.firstName()),
            blankToNull(command.lastName()),
            blankToNull(command.phoneNumber()));
    account.attachPasswordCredential(hasher.hash(command.rawPassword()));
    if (username != null) {
      account.assignUsername(username);
    }
    // An operator creating the account directly is vouching for the email — see this class's own
    // Javadoc for why this differs from self-service registration's always-sends-a-verification-
    // email posture.
    account.verifyEmail();

    accounts.insert(account);
    return account.id();
  }

  private Username validateUsername(final AdminCreateAccountForOrganizationCommand command) {
    final String rawUsername = blankToNull(command.username());
    if (rawUsername == null) {
      return null;
    }
    final Username username = new Username(rawUsername);
    if (accounts.existsByOrganizationIdAndUsername(command.organizationId(), username)) {
      throw new UsernameAlreadyRegisteredException(command.organizationId());
    }
    return username;
  }

  private static String blankToNull(final String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
