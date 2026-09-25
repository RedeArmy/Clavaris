package com.clavaris.webhook.infrastructure.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Live UX request, 2026-09-25: a fresh, single-use, short-TTL server-side token for {@link
 * PlatformDeleteWebhookEndpointController}'s own two-step confirmation flow — same shape {@code
 * client-registry-module}'s own {@code DeleteOAuthClientConfirmationTokens} already establishes for
 * an identical irreversible dashboard action, copied here rather than shared (that class is
 * package-private to a different module's package). Keyed by {@code endpointId} (a {@link UUID}
 * here, unlike {@code DeleteOAuthClientConfirmationTokens}' own string {@code clientId} — this
 * module's own natural key for a {@code WebhookEndpoint}).
 *
 * <p>{@link #issue} mints a fresh token on every confirm-page render, overwriting any prior one for
 * that {@code endpointId}; {@link #consume} always removes the session attribute on its very first
 * check, whether the submitted token matches or not — a stale confirm page, reloaded or
 * auto-submitted a second time, can never re-trigger a delete with the same token.
 */
@SuppressWarnings("PMD.LongVariable")
final class DeleteWebhookEndpointConfirmationTokens {

  private static final String SESSION_ATTRIBUTE_PREFIX =
      "clavaris.deleteWebhookEndpoint.confirmationToken.";
  private static final Duration TTL = Duration.ofMinutes(10);

  private DeleteWebhookEndpointConfirmationTokens() {
    // Utility class — never instantiated.
  }

  /* package */ static String issue(final HttpServletRequest request, final UUID endpointId) {
    final String token = UUID.randomUUID().toString();
    request
        .getSession(true)
        .setAttribute(attributeName(endpointId), new Entry(token, Instant.now().plus(TTL)));
    return token;
  }

  // PMD.OnlyOneReturn: two distinct "not valid" exits (no session, no/expired entry) plus the
  // real match check — same rationale as DeleteOAuthClientConfirmationTokens' own identical
  // suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  /* package */ static boolean consume(
      final HttpServletRequest request, final UUID endpointId, final String submittedToken) {
    final HttpSession session = request.getSession(false);
    if (session == null) {
      return false;
    }
    final String attributeName = attributeName(endpointId);
    final Object raw = session.getAttribute(attributeName);
    // Single-use: removed on this very first check, regardless of the outcome below.
    session.removeAttribute(attributeName);
    if (!(raw instanceof Entry(String token, Instant expiresAt))
        || Instant.now().isAfter(expiresAt)) {
      return false;
    }
    return token.equals(submittedToken);
  }

  private static String attributeName(final UUID endpointId) {
    return SESSION_ATTRIBUTE_PREFIX + endpointId;
  }

  // This app's own HttpSession is Redis-backed (Spring Session, not in-memory) — any object
  // stored as a session attribute must survive real serialization, not just work by accident in a
  // single-JVM test.
  private record Entry(String token, Instant expiresAt) implements Serializable {

    private static final long serialVersionUID = 1L;
  }
}
