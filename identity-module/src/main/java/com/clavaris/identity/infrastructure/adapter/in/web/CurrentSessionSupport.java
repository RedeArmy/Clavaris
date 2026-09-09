package com.clavaris.identity.infrastructure.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.Optional;

/**
 * SDE-III optimization pass, P2 point 3: {@link AccountSessionsController} and {@link
 * PlatformAccountSessionsController} were investigated for the same class of mirror duplication
 * {@code TD-ARCH-016} already closed for {@code LoginController}/{@code UsernameSignInController} —
 * confirmed real this time (unlike {@code LoginController}/{@code PlatformLoginController}, which a
 * direct diff showed are genuinely different flows, not a mirror; see the register's own entry for
 * that negative finding). The two provably-identical fragments both controllers repeated — reading
 * the current request's own {@code HttpSession} id, and the "resolve the authenticated principal or
 * fail loudly" guard each controller's own security chain guarantees is unreachable in practice —
 * live here instead, same "small shared utility class" precedent {@link UserAgentLabel} already
 * establishes for this identical page pair.
 *
 * <p>Deliberately NOT a shared base controller class or a unified resolver interface: {@code
 * AccountId}/{@code PlatformAccountId} and every query/command/exception type each controller's own
 * {@code showSessions}/{@code revoke} method uses are genuinely separate types with no common
 * supertype, and unifying those just to share these two small fragments would be the exact "bigger,
 * riskier refactor for a marginal gain" trade-off {@code TD-ARCH-009}'s own Service-tier entry
 * already rejected for a structurally similar case. This stays a plain static utility, generic only
 * over the one piece that's actually generic ({@link #requireResolved}'s own principal type).
 */
final class CurrentSessionSupport {

  private CurrentSessionSupport() {}

  /**
   * The current request's own {@code HttpSession} id, or {@code null} if none exists yet — lets a
   * sessions-list template mark/label the row for the browser making this very request without the
   * calling controller needing to duplicate any session-lookup logic of its own.
   */
  /* package */ static String currentSessionId(final HttpServletRequest request) {
    final HttpSession currentSession = request.getSession(false);
    return currentSession == null ? null : currentSession.getId();
  }

  /**
   * Unwraps an already-resolved principal {@link Optional}, or fails loudly with a descriptive
   * {@link IllegalStateException} — never actually reachable in practice (each caller's own
   * security filter chain guarantees an authenticated principal before its controller ever runs),
   * but an explicit, checked failure here is still safer than letting a {@code
   * NoSuchElementException} escape unexplained.
   *
   * @param resolved the already-attempted resolution (e.g. {@code currentAccount.resolve(request)})
   * @param principalTypeName names what's missing in the thrown message, e.g. {@code "Account"} or
   *     {@code "PlatformAccount"}
   */
  /* package */ static <T> T requireResolved(
      final Optional<T> resolved, final String principalTypeName) {
    return resolved.orElseThrow(
        () ->
            new IllegalStateException(
                "No authenticated " + principalTypeName + " on this request"));
  }
}
