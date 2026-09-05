package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.Optional;
import java.util.UUID;

/**
 * Clerk "session tasks" parity: the shared "does this just-authenticated login have a required task
 * still outstanding?" check every primary-factor controller ({@link LoginController}, {@link
 * UsernameSignInController}, {@link EmailCodeSignInController}, {@link EmailLinkSignInController})
 * runs right after its own {@code Authenticate*UseCase} succeeds and — critically — after {@link
 * DeviceTrustGate#intercept} has already returned empty (a login can be paused for one, then the
 * other, but this one only ever runs once the device-trust gate has already let the login through,
 * same ordering every caller follows).
 *
 * <p><b>Only one concrete task exists in this pass</b> — an operator-forced password reset ({@code
 * ForcePasswordResetForAccountUseCase}/{@code Account#requirePasswordReset}) — unlike Clerk's own
 * three ({@code choose-organization}, {@code setup-mfa}, {@code reset-password}): this codebase has
 * no MFA to set up and no multi-Workspace-membership ambiguity to resolve (see {@code
 * WorkspaceRoleClaimsCustomizer}'s own Javadoc for why an Account can only ever belong to one
 * Workspace today), so those two map to states this codebase structurally cannot have. Never issues
 * an authorization code/token for a session with a task still outstanding — the mechanism is "defer
 * {@code AuthenticatedSessionEstablisher} entirely," exactly {@link DeviceTrustGate}'s own proven
 * shape, not a frontend-visible {@code pending} JWT the way Clerk's own SPA session model needs
 * (this redirect-based architecture has no equivalent state to expose, which is a deliberate,
 * documented divergence, not a gap).
 */
final class SessionTaskGate {

  private SessionTaskGate() {
    // Static utility — not instantiable, same shape as DeviceTrustGate.
  }

  /**
   * @return the session-task challenge redirect URL when this login must be paused; empty when no
   *     task is outstanding.
   */
  // Two genuinely distinct outcomes (must pause / may proceed) — same rationale as
  // DeviceTrustGate's own identical suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  /* package */ static Optional<String> intercept(
      final AccountRepository accounts,
      final HttpServletRequest request,
      final UUID organizationId,
      final AccountId accountId,
      final PendingAuthenticationFactor factor,
      // Clerk "customize redirect URLs" parity — both nullable, see DeviceTrustGate's own
      // identical parameters.
      final String clientId,
      final String redirectUrl) {
    final Optional<Account> account = accounts.findById(accountId);
    // Absent is treated as "nothing outstanding," not an error — the caller's own
    // Authenticate*UseCase already proved this account exists moments ago; a genuinely missing
    // account here would surface as a 500 further down this same request regardless.
    if (account.isEmpty() || account.get().passwordResetRequiredAt().isEmpty()) {
      return Optional.empty();
    }

    final HttpSession session = request.getSession(true);
    session.setAttribute(
        SessionTaskPendingState.ACCOUNT_ID_ATTRIBUTE, accountId.value().toString());
    session.setAttribute(SessionTaskPendingState.FACTOR_ATTRIBUTE, factor.name());
    session.setAttribute(
        SessionTaskPendingState.ORGANIZATION_ID_ATTRIBUTE, organizationId.toString());
    if (clientId != null) {
      session.setAttribute(SessionTaskPendingState.CLIENT_ID_ATTRIBUTE, clientId);
    }
    if (redirectUrl != null) {
      session.setAttribute(SessionTaskPendingState.REDIRECT_URL_ATTRIBUTE, redirectUrl);
    }

    return Optional.of("/o/" + organizationId + "/login/session-task/password-reset");
  }
}
