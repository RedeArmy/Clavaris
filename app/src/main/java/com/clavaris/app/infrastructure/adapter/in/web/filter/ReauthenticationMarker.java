package com.clavaris.app.infrastructure.adapter.in.web.filter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;

/**
 * The note {@link OidcAuthorizationRequestParamsFilter} leaves in the session when it asks the
 * End-User for a fresh login (prompt=login, or a max_age the session has outlived): when, and for
 * which request. Once the End-User has signed in again, the saved request resumes and passes
 * through because this note says that login was the one asked for, so there is no redirect loop.
 *
 * <p>The note never outlives its purpose: it expires after {@link #TTL}, counts only for the very
 * request it was left for, and is cleared by any other authorize request, so an abandoned request
 * can never let a later prompt=login through.
 */
// PMD.LawOfDemeter: request.getSession() is the standard Servlet API shape, same rationale as the
// sibling filters in this package. PMD.OnlyOneReturn: "consumed" and "not consumed" are two
// distinct outcomes.
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.OnlyOneReturn"})
/* package */ final class ReauthenticationMarker {

  /* package */ static final String SESSION_ATTRIBUTE = "clavaris.oidc.reauth.requested-at";

  // Long enough to sign in again, short enough that an abandoned note cannot linger.
  /* package */ static final Duration TTL = Duration.ofMinutes(10);

  private ReauthenticationMarker() {}

  // When, and for which request. Serializable because the session is shared through Redis.
  /* package */ record Note(Instant requestedAt, String request) implements Serializable {}

  /* package */ static Note create(final HttpServletRequest request, final Instant now) {
    return new Note(now, fingerprint(request));
  }

  // The request's parameters, sorted, as a string. Not the raw query string: the saved request that
  // resumes after the login carries an extra "continue" parameter (Spring Security's own marker)
  // and may order or encode the rest differently, but it is the same request.
  private static String fingerprint(final HttpServletRequest request) {
    final StringBuilder result = new StringBuilder();
    new TreeMap<>(request.getParameterMap())
        .forEach(
            (name, values) -> {
              if (!"continue".equals(name)) {
                final String[] sorted = values.clone();
                Arrays.sort(sorted);
                result.append(name).append('=').append(String.join(",", sorted)).append('&');
              }
            });
    return result.toString();
  }

  private static boolean isFor(
      final Note note, final HttpServletRequest request, final Instant now) {
    return note.request().equals(fingerprint(request))
        && Duration.between(note.requestedAt(), now).compareTo(TTL) <= 0;
  }

  // Leaves the note for this request (the End-User is about to sign in again).
  /* package */ static void leave(final HttpServletRequest request, final Instant now) {
    request.getSession().setAttribute(SESSION_ATTRIBUTE, create(request, now));
  }

  // With no one signed in the End-User gets the login page anyway; noting that this very request
  // asked for a fresh login lets the resumed request pass after that one login instead of asking
  // for a second.
  /* package */ static void leaveIfFreshLoginWanted(
      final HttpServletRequest request,
      final List<String> prompts,
      final Long maxAge,
      final Instant now) {
    if (prompts.contains("login") || Long.valueOf(0).equals(maxAge)) {
      leave(request, now);
    }
  }

  // True, and the note is used up, when it was left for this very request and the End-User has
  // signed in again since.
  /* package */ static boolean consumedByLoginSince(
      final HttpServletRequest request, final Instant latestFactorAt, final Instant now) {
    final HttpSession session = request.getSession(false);
    final Object stored = session == null ? null : session.getAttribute(SESSION_ATTRIBUTE);
    if (stored instanceof Note note
        && isFor(note, request, now)
        && latestFactorAt.isAfter(note.requestedAt())) {
      session.removeAttribute(SESSION_ATTRIBUTE);
      return true;
    }
    return false;
  }

  // Any other authorize request (a new one, a different one, one that arrives after the note
  // expired) clears a leftover note.
  /* package */ static void discardUnlessFor(final HttpServletRequest request, final Instant now) {
    final HttpSession session = request.getSession(false);
    final Object stored = session == null ? null : session.getAttribute(SESSION_ATTRIBUTE);
    if (stored != null && !(stored instanceof Note note && isFor(note, request, now))) {
      session.removeAttribute(SESSION_ATTRIBUTE);
    }
  }
}
