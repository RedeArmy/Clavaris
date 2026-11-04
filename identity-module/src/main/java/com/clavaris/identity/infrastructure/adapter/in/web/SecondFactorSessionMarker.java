package com.clavaris.identity.infrastructure.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.UUID;

/**
 * ADR-0040: the proof, kept in the session of the sign-in in progress, that the authenticator code
 * of one Account was just accepted. {@link TotpChallengeController} sets it and the session
 * establisher consumes it, once, when it builds the authenticated session: that is how the second
 * factor reaches the {@code amr} claim, and how the establisher can refuse to build a session for
 * an Account that needs a second factor and has not shown one, which keeps a call site that forgot
 * the gate from silently skipping the factor.
 */
public final class SecondFactorSessionMarker {

  private static final String ATTRIBUTE = "clavaris.secondFactor.verifiedAccount";

  private SecondFactorSessionMarker() {
    // Static utility.
  }

  /* package */ static void mark(final HttpSession session, final UUID accountId) {
    session.setAttribute(ATTRIBUTE, accountId.toString());
  }

  /**
   * True when this sign-in just proved the second factor of {@code accountId}. The marker is
   * removed either way, so it can be used for one session only.
   */
  public static boolean consume(final HttpServletRequest request, final UUID accountId) {
    final HttpSession session = request.getSession(false);
    if (session == null) {
      return false;
    }
    final Object marked = session.getAttribute(ATTRIBUTE);
    session.removeAttribute(ATTRIBUTE);
    return accountId.toString().equals(marked);
  }
}
