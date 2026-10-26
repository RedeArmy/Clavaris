package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
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
final class DeleteOAuthClientConfirmationTokens {

  private static final String SESSION_ATTRIBUTE_PREFIX =
      "clavaris.deleteOAuthClient.confirmationToken.";
  private static final Duration TTL = Duration.ofMinutes(10);
  private static final char SEPARATOR = '|';

  private DeleteOAuthClientConfirmationTokens() {
    // Utility class — never instantiated.
  }

  /* package */ static String issue(final HttpServletRequest request, final String clientId) {
    final String token = UUID.randomUUID().toString();
    request
        .getSession(true)
        .setAttribute(attributeName(clientId), encode(token, Instant.now().plus(TTL)));
    return token;
  }

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
    if (!(raw instanceof String stored)) {
      return false;
    }
    final int separator = stored.indexOf(SEPARATOR);
    if (separator < 0) {
      return false;
    }
    final Instant expiresAt;
    try {
      expiresAt = Instant.ofEpochMilli(Long.parseLong(stored.substring(separator + 1)));
    } catch (final NumberFormatException _) {
      return false;
    }
    return !Instant.now().isAfter(expiresAt)
        && stored.substring(0, separator).equals(submittedToken);
  }

  private static String attributeName(final String clientId) {
    return SESSION_ATTRIBUTE_PREFIX + clientId;
  }

  // TD-ARCH-039: a session attribute is a plain String (token and expiry joined), so the Redis
  // session serializer can store it as JSON without a type of ours in its allow-list. The token is
  // a UUID, so the separator cannot occur in it.
  private static String encode(final String token, final Instant expiresAt) {
    return token + SEPARATOR + expiresAt.toEpochMilli();
  }
}
