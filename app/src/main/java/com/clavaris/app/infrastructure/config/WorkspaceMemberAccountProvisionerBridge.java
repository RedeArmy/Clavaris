package com.clavaris.app.infrastructure.config;

import com.clavaris.identity.application.usecase.registeraccount.EmailAlreadyRegisteredException;
import com.clavaris.identity.application.usecase.registeraccount.RegisterAccountCommand;
import com.clavaris.identity.application.usecase.registeraccount.RegisterAccountUseCase;
import com.clavaris.identity.application.usecase.requestpasswordreset.RequestPasswordResetCommand;
import com.clavaris.identity.application.usecase.requestpasswordreset.RequestPasswordResetUseCase;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.organization.application.usecase.addworkspacemember.AccountProvisioner;
import java.security.SecureRandom;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Implements organization-module's {@link AccountProvisioner} — the bridge lives in {@code app},
 * not either business module, same module-graph reason {@code CreateOrganizationSigningKeyBridge}
 * already establishes.
 *
 * <p>BR-WS-04: 100% reuse of already-built, already-tested identity-module use cases — no new mail
 * template, no new credential-issuance path. {@link #generateRandomPassword()} produces a value
 * that satisfies {@code PasswordPolicy} (8–128 chars) but is never returned to any caller, logged,
 * or otherwise retained after this method returns — it exists purely to satisfy BR-ID-02's "an
 * Account is never valid with zero authentication methods" invariant for the instant between
 * account creation and the new member setting their own real password via the reset-link email
 * below.
 */
// PMD.LongVariable: GENERATED_PASSWORD_LENGTH and requestPasswordReset (field/param) both name
// exactly what they are — a shortened identifier would only make this class harder to read, same
// convention every other descriptively-named port/constant in this codebase follows.
@SuppressWarnings("PMD.LongVariable")
@Component
class WorkspaceMemberAccountProvisionerBridge implements AccountProvisioner {

  // Long enough that brute-forcing this never-returned, never-logged, immediately-superseded
  // value is not a meaningful attack surface; well within PasswordPolicy's own 128-char ceiling.
  private static final int GENERATED_PASSWORD_LENGTH = 32;
  private static final String PASSWORD_ALPHABET =
      "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%^&*";

  private final RegisterAccountUseCase registerAccount;
  private final RequestPasswordResetUseCase requestPasswordReset;
  private final SecureRandom secureRandom = new SecureRandom();

  /* package */ WorkspaceMemberAccountProvisionerBridge(
      final RegisterAccountUseCase registerAccount,
      final RequestPasswordResetUseCase requestPasswordReset) {
    this.registerAccount = registerAccount;
    this.requestPasswordReset = requestPasswordReset;
  }

  @Override
  public ProvisionedAccount provisionAndSendWelcome(final UUID organizationId, final String email) {
    final OrganizationId orgId = new OrganizationId(organizationId);
    final Email memberEmail = new Email(email);

    final AccountId accountId;
    try {
      accountId =
          registerAccount.handle(
              new RegisterAccountCommand(orgId, memberEmail, generateRandomPassword()));
    } catch (final EmailAlreadyRegisteredException alreadyRegistered) {
      // Never let identity-module's own exception type cross the module boundary — see this
      // port's own Javadoc. Cause preserved, not discarded, same precedent
      // EmailAlreadyRegisteredException's own two-constructor shape already establishes.
      throw new AccountAlreadyExistsException(organizationId, email, alreadyRegistered);
    }

    // Deliberately after RegisterAccountUseCase's own transaction has already committed — same
    // "no DB transaction held open across a network call" discipline this class's own port
    // documents. Reuses the existing forgot-password flow verbatim as the new member's own
    // "set your first password" onboarding step.
    requestPasswordReset.handle(new RequestPasswordResetCommand(orgId, memberEmail));

    return new ProvisionedAccount(accountId.value());
  }

  private String generateRandomPassword() {
    final StringBuilder password = new StringBuilder(GENERATED_PASSWORD_LENGTH);
    for (int i = 0; i < GENERATED_PASSWORD_LENGTH; i++) {
      password.append(PASSWORD_ALPHABET.charAt(secureRandom.nextInt(PASSWORD_ALPHABET.length())));
    }
    return password.toString();
  }
}
