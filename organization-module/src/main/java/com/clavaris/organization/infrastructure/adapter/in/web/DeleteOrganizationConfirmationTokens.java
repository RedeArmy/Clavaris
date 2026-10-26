package com.clavaris.organization.infrastructure.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * TD-FUT-032: a fresh, single-use, short-TTL server-side token for {@link
 * PlatformDeleteOrganizationController}'s own two-step confirmation flow — see that class's own
 * Javadoc for the full design and why the ordinary {@code _csrf} token (already present on every
 * form in this codebase) is necessary but not sufficient for the single most destructive action
 * this dashboard exposes.
 *
 * <p>{@link HttpSession}-backed, not persisted — same "ephemeral, short-lived by nature" posture
 * {@code DeviceTrustPendingState}'s own session-attribute convention already establishes for an
 * unrelated pause/resume flow, deliberately not a new persisted domain concept for something this
 * narrow. {@link #issue} mints a fresh token on every confirm-page render, overwriting any prior
 * one for that {@code organizationId}; {@link #consume} always removes the session attribute on its
 * very first check, whether the submitted token matches or not — a stale confirm page, reloaded or
 * auto-submitted a second time, can never re-trigger a delete with the same token.
 *
 * <p>PMD.LongVariable: {@code SESSION_ATTRIBUTE_PREFIX} names exactly what it is, not arbitrarily
 * long — same precedent every other codebase-wide suppression of this rule already establishes.
 */
final class DeleteOrganizationConfirmationTokens {

  private static final String SESSION_ATTRIBUTE_PREFIX =
      "clavaris.deleteOrganization.confirmationToken.";
  private static final Duration TTL = Duration.ofMinutes(10);
  private static final char SEPARATOR = '|';

  private DeleteOrganizationConfirmationTokens() {
    // Utility class — never instantiated.
  }

  /* package */ static String issue(final HttpServletRequest request, final UUID organizationId) {
    final String token = UUID.randomUUID().toString();
    request
        .getSession(true)
        .setAttribute(attributeName(organizationId), encode(token, Instant.now().plus(TTL)));
    return token;
  }

  /* package */ static boolean consume(
      final HttpServletRequest request, final UUID organizationId, final String submittedToken) {
    final HttpSession session = request.getSession(false);
    if (session == null) {
      return false;
    }
    final String attributeName = attributeName(organizationId);
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

  private static String attributeName(final UUID organizationId) {
    return SESSION_ATTRIBUTE_PREFIX + organizationId;
  }

  // TD-ARCH-039: a session attribute is a plain String (token and expiry joined), so the Redis
  // session serializer can store it as JSON without a type of ours in its allow-list. The token is
  // a UUID, so the separator cannot occur in it.
  private static String encode(final String token, final Instant expiresAt) {
    return token + SEPARATOR + expiresAt.toEpochMilli();
  }
}
