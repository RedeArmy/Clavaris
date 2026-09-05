package com.clavaris.identity.application.usecase.registeraccount;

import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicyProvider;
import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicySnapshot;
import com.clavaris.identity.domain.event.AccountRegisteredEvent;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.Username;
import com.clavaris.identity.domain.service.PasswordPolicy;
import com.clavaris.identity.domain.service.RandomPasswordGenerator;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestration for {@link RegisterAccountUseCase}. {@code @Transactional} is the one place Spring
 * leaks into this class — every type it depends on otherwise (the domain model, the ports) has zero
 * Spring imports.
 *
 * <p>ADR-0024 §4/§5: also validates the optional {@code username} (required/uniqueness per policy)
 * and, when the Organization's own {@code passwordAtSignUpEnabled} policy is off and the caller
 * submitted no password, attaches a real, cryptographically random one instead of none at all — see
 * {@link RandomPasswordGenerator}'s own Javadoc for why this, not a zero-credential row, is what
 * satisfies BR-ID-02 for a passwordless signup. The account holder's actual first sign-in still
 * happens through the passwordless email flow (ADR-0024 §3), never this generated value.
 */
// PMD.LongVariable: rawPasswordToAttach names exactly what it is. PMD.OnlyOneReturn: both helper
// methods have genuinely distinct early-exit branches (not required/no value vs. required/missing
// vs. resolved), same "one exit per distinct outcome" rationale this codebase's own guard-clause-
// heavy resolution logic already documents elsewhere (e.g. LoginController's own identical
// suppression).
@SuppressWarnings({"PMD.LongVariable", "PMD.OnlyOneReturn"})
public class RegisterAccountService implements RegisterAccountUseCase {

  private final AccountRepository accounts;
  private final PasswordHasher hasher;
  private final EventOutboxWriter outbox;
  private final AccountAuthenticationPolicyProvider policyProvider;

  public RegisterAccountService(
      final AccountRepository accounts,
      final PasswordHasher hasher,
      final EventOutboxWriter outbox,
      final AccountAuthenticationPolicyProvider policyProvider) {
    this.accounts = accounts;
    this.hasher = hasher;
    this.outbox = outbox;
    this.policyProvider = policyProvider;
  }

  @Override
  @Transactional
  public AccountId handle(final RegisterAccountCommand command) {
    final AccountAuthenticationPolicySnapshot policy =
        policyProvider.policyFor(command.organizationId());
    final Username username = validateUsername(command, policy);
    final String rawPasswordToAttach = resolveRawPassword(command, policy);

    // Concurrency: two requests can race to register the same email *within the same
    // Organization* between this check and the insert below. This pre-check is a fast-path UX
    // improvement (return a clean 409 instead of surfacing a low-level DB error most of the
    // time), NOT the actual safety mechanism — that's the unique constraint on
    // accounts.(organization_id, email) (data-model.md §3, ADR-0010). The same email in a
    // DIFFERENT Organization is not a conflict at all — command.organizationId() scopes this
    // check by design (BR-ORG-01).
    if (accounts.existsByOrganizationIdAndEmail(command.organizationId(), command.email())) {
      throw new EmailAlreadyRegisteredException(command.organizationId());
    }

    final Account account = Account.register(command.organizationId(), command.email());
    account.attachPasswordCredential(hasher.hash(rawPasswordToAttach)); // BR-ID-01/BR-ID-02
    if (username != null) {
      account.assignUsername(username);
    }

    try {
      accounts.save(account);
    } catch (DataIntegrityViolationException raceLost) {
      // The pre-check above lost the race — another request committed first. Translate the
      // low-level DB exception into the same domain exception the pre-check would have thrown, so
      // the caller (the web adapter) never needs to know a race occurred — but keep raceLost as
      // the cause, not discard it, so a production investigation still has the real JDBC-level
      // detail available.
      //
      // ADR-0024 §4 known gap, named not hidden: a concurrent username race (rather than an email
      // race) would also land here and be reported as an email conflict — the two unique
      // constraints can't be distinguished from this generic exception type without inspecting the
      // driver-specific constraint name, which this codebase's persistence layer deliberately
      // doesn't do anywhere else either. Same low-probability, "the pre-check already narrowed
      // this to a genuine simultaneous race" caveat the email case itself already carries.
      throw new EmailAlreadyRegisteredException(command.organizationId(), raceLost);
    }

    // ADR-0007 §1: outbox row written in the SAME transaction as the account insert —
    // @Transactional above covers both, so a crash between the two is impossible; either both
    // commit or neither does.
    outbox.write(
        "account.created",
        account.id(),
        account.organizationId(),
        AccountRegisteredEvent.from(account));

    return account.id();
  }

  private Username validateUsername(
      final RegisterAccountCommand command, final AccountAuthenticationPolicySnapshot policy) {
    final boolean submitted = command.rawUsername() != null && !command.rawUsername().isBlank();
    if (!submitted) {
      if (policy.usernameRequired()) {
        throw new UsernameRequiredException();
      }
      return null;
    }
    final Username username = new Username(command.rawUsername());
    // Same fast-path-pre-check-only caveat as the email check in handle() above.
    if (accounts.existsByOrganizationIdAndUsername(command.organizationId(), username)) {
      throw new UsernameAlreadyRegisteredException(command.organizationId());
    }
    return username;
  }

  private String resolveRawPassword(
      final RegisterAccountCommand command, final AccountAuthenticationPolicySnapshot policy) {
    final boolean submitted = command.rawPassword() != null && !command.rawPassword().isBlank();
    if (submitted) {
      if (!PasswordPolicy.isSatisfiedBy(command.rawPassword())) {
        throw new WeakPasswordException();
      }
      return command.rawPassword();
    }
    if (policy.passwordAtSignUpEnabled()) {
      // No password submitted but the Organization requires one — PasswordPolicy.isSatisfiedBy
      // already rejects null/blank, so this is the same WeakPasswordException a real-but-too-short
      // password would get, not a new distinct case the caller needs to handle differently.
      throw new WeakPasswordException();
    }
    return RandomPasswordGenerator.generate();
  }
}
