package com.clavaris.organization.application.usecase.addworkspacemember;

import java.util.UUID;

/**
 * Outbound port (BR-WS-04): creates a real identity-module {@code Account} for a new workspace
 * member and starts its "set your own password" flow — no invitation exists in v1, so provisioning
 * an {@code Account} directly is the entire onboarding mechanism. Deliberately does NOT reference
 * any identity-module type directly — organization-module and identity-module stay mutually
 * independent business modules, same convention {@code SigningKeyProvisioner} already established
 * for its own cross-module boundary. Implemented in {@code app}, the one module allowed to depend
 * on both, by {@code WorkspaceMemberAccountProvisionerBridge}.
 *
 * <p>Deliberately synchronous and deliberately NOT called from inside {@link
 * AddWorkspaceMemberService}'s own {@code @Transactional} step: the real implementation both writes
 * to identity-module's own database (its own, already-committing transaction) and sends a real
 * email over the network — same "no DB transaction held open across a network call" discipline
 * identity-module's own {@code RequestPasswordResetService} already documents for itself.
 */
@FunctionalInterface
public interface AccountProvisioner {

  /**
   * @throws AccountAlreadyExistsException if {@code email} is already registered in {@code
   *     organizationId} — translated from identity-module's own {@code
   *     EmailAlreadyRegisteredException}, never let to cross the module boundary as-is.
   */
  ProvisionedAccount provisionAndSendWelcome(UUID organizationId, String email);

  /** Just enough to attach a {@code WorkspaceMembership} row to the new Account. */
  record ProvisionedAccount(UUID accountId) {}

  /** BR-WS-04: the target email is already registered in this Organization's own account pool. */
  final class AccountAlreadyExistsException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AccountAlreadyExistsException(final UUID organizationId, final String email) {
      super(
          "An account with email '"
              + email
              + "' is already registered in organization "
              + organizationId);
    }

    /**
     * Same message, plus the low-level identity-module exception that revealed the conflict —
     * preserves its stack trace instead of discarding it, same "never silently drop the real cause"
     * precedent {@code EmailAlreadyRegisteredException}'s own two-constructor shape already
     * establishes.
     */
    public AccountAlreadyExistsException(
        final UUID organizationId, final String email, final Throwable cause) {
      super(
          "An account with email '"
              + email
              + "' is already registered in organization "
              + organizationId,
          cause);
    }
  }
}
