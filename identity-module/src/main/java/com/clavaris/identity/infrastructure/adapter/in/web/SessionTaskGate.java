package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.domain.model.Account;
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
 * <p>TD-PERF-015 (closed): {@link #intercept} used to take an {@code AccountId} and its own {@code
 * AccountRepository}, re-fetching the exact {@link Account} row the caller's own {@code
 * Authenticate*UseCase} had already loaded moments earlier — the caller's own comment already
 * admitted as much ("the caller's own Authenticate*UseCase already proved this account exists
 * moments ago"). Every real caller of this method is one of the four primary-factor controllers
 * above, and every one of them now has that {@link Account} in hand (TD-PERF-015's own {@code
 * Authenticate*UseCase} return-type change) — so this takes it directly instead.
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
      final HttpServletRequest request,
      final UUID organizationId,
      final Account account,
      final PendingAuthenticationFactor factor,
      // Clerk "customize redirect URLs" parity — both nullable, see DeviceTrustGate's own
      // identical parameters.
      final String clientId,
      final String redirectUrl) {
    if (account.passwordResetRequiredAt().isEmpty()) {
      return Optional.empty();
    }

    final HttpSession session = request.getSession(true);
    session.setAttribute(
        SessionTaskPendingState.ACCOUNT_ID_ATTRIBUTE, account.id().value().toString());
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
