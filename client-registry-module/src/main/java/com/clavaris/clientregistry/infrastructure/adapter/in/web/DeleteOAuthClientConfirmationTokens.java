package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Live UX request, 2026-09-24: a fresh, single-use, short-TTL server-side token for {@link
 * PlatformDeleteOAuthClientController}'s own two-step confirmation flow — the same shape {@code
 * organization.infrastructure.adapter.in.web.DeleteOrganizationConfirmationTokens} already
 * establishes for this codebase's only other genuinely irreversible dashboard action, copied here
 * rather than shared (that class is package-private to a different module's package, and this
 * pattern is small enough that a second, deliberately-identical copy is simpler than threading a
 * shared abstraction across the module boundary for one utility class).
 *
 * <p>{@link HttpSession}-backed, not persisted, keyed by {@code clientId} (this credential's own
 * natural key everywhere else in this module) rather than a numeric id. {@link #issue} mints a
 * fresh token on every confirm-page render, overwriting any prior one for that {@code clientId};
 * {@link #consume} always removes the session attribute on its very first check, whether the
 * submitted token matches or not — a stale confirm page, reloaded or auto-submitted a second time,
 * can never re-trigger a delete with the same token.
 */
@SuppressWarnings("PMD.LongVariable")
final class DeleteOAuthClientConfirmationTokens {

  private static final String SESSION_ATTRIBUTE_PREFIX =
      "clavaris.deleteOAuthClient.confirmationToken.";
  private static final Duration TTL = Duration.ofMinutes(10);

  private DeleteOAuthClientConfirmationTokens() {
    // Utility class — never instantiated.
  }

  /* package */ static String issue(final HttpServletRequest request, final String clientId) {
    final String token = UUID.randomUUID().toString();
    request
        .getSession(true)
        .setAttribute(attributeName(clientId), new Entry(token, Instant.now().plus(TTL)));
    return token;
  }

  // PMD.OnlyOneReturn: two distinct "not valid" exits (no session, no/expired entry) plus the
  // real match check — same rationale as DeleteOrganizationConfirmationTokens' own identical
  // suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  /* package */ static boolean consume(
      final HttpServletRequest request, final String clientId, final String submittedToken) {
    final HttpSession session = request.getSession(false);
    if (session == null) {
      return false;
    }
    final String attributeName = attributeName(clientId);
    final Object raw = session.getAttribute(attributeName);
    // Single-use: removed on this very first check, regardless of the outcome below.
    session.removeAttribute(attributeName);
    if (!(raw instanceof Entry(String token, Instant expiresAt))
        || Instant.now().isAfter(expiresAt)) {
      return false;
    }
    return token.equals(submittedToken);
  }

  private static String attributeName(final String clientId) {
    return SESSION_ATTRIBUTE_PREFIX + clientId;
  }

  // This app's own HttpSession is Redis-backed (Spring Session, not in-memory) — any object
  // stored as a session attribute must survive real serialization, not just work by accident in a
  // single-JVM test.
  private record Entry(String token, Instant expiresAt) implements Serializable {

    private static final long serialVersionUID = 1L;
  }
}
