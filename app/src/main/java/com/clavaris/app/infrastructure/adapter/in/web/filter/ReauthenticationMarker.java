package com.clavaris.app.infrastructure.adapter.in.web.filter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The notes {@link OidcAuthorizationRequestParamsFilter} leaves in the session when it asks the
 * End-User for a fresh login (prompt=login, or a max_age the session has outlived): when, and for
 * which request. Once the End-User has signed in again, the saved request resumes and passes
 * through because a note says that login was the one asked for, so there is no redirect loop.
 *
 * <p>There is one note per request (identified by its parameters), not a single slot: another
 * authorize request made while the End-User is on the login page (a second tab, a retry) must not
 * wipe the note of the request that is waiting, or its resumption would ask for a second login. The
 * notes are bounded ({@link #MAX_NOTES}, the oldest goes first) and each expires after {@link
 * #TTL}, so abandoned requests cannot pile up or let a later prompt=login through: a note counts
 * only for the very request it was left for, only until it expires, and only for a login made after
 * it was left, and it is used up when it counts.
 */
/* package */ final class ReauthenticationMarker {

  /* package */ static final String SESSION_ATTRIBUTE = "clavaris.oidc.reauth.requested-at";

  // Long enough to sign in again, short enough that an abandoned note cannot linger.
  /* package */ static final Duration TTL = Duration.ofMinutes(10);

  // More than the requests one browser plausibly has waiting for a login at once.
  /* package */ static final int MAX_NOTES = 5;

  private ReauthenticationMarker() {}

  // TD-ARCH-039: the session attribute is a plain Map of request fingerprint to the ISO-8601
  // instant the note was left, both Strings, so the Redis session serializer (JSON, with an
  // allow-list) stores it with no type of ours in that list. A copy is stored on every change so
  // that the session sees the new value.
  /* package */ static Map<String, String> create(
      final HttpServletRequest request, final Instant now) {
    final Map<String, String> notes = new LinkedHashMap<>();
    notes.put(fingerprint(request), now.toString());
    return notes;
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

  private static boolean expired(final Instant leftAt, final Instant now) {
    return Duration.between(leftAt, now).compareTo(TTL) > 0;
  }

  // The notes still alive, as a copy that can be changed freely.
  private static Map<String, Instant> liveNotes(final HttpSession session, final Instant now) {
    final Map<String, Instant> live = new LinkedHashMap<>();
    storedNotes(session)
        .forEach(
            (request, leftAt) -> {
              if (!expired(leftAt, now)) {
                live.put(request, leftAt);
              }
            });
    return live;
  }

  private static void store(final HttpSession session, final Map<String, Instant> notes) {
    if (notes.isEmpty()) {
      session.removeAttribute(SESSION_ATTRIBUTE);
    } else {
      final Map<String, String> asStrings = new LinkedHashMap<>();
      notes.forEach((request, leftAt) -> asStrings.put(request, leftAt.toString()));
      session.setAttribute(SESSION_ATTRIBUTE, asStrings);
    }
  }

  // What the session holds, parsed; an entry that is not a String-to-ISO-instant pair is ignored.
  private static Map<String, Instant> storedNotes(final HttpSession session) {
    final Map<String, Instant> stored = new LinkedHashMap<>();
    if (session != null && session.getAttribute(SESSION_ATTRIBUTE) instanceof Map<?, ?> raw) {
      raw.forEach(
          (key, value) -> {
            if (key instanceof String request && value instanceof String leftAt) {
              parseInstant(leftAt).ifPresent(parsed -> stored.put(request, parsed));
            }
          });
    }
    return stored;
  }

  // Not one of ours when it does not parse; such an entry is dropped the next time the notes are
  // stored.
  private static Optional<Instant> parseInstant(final String value) {
    try {
      return Optional.of(Instant.parse(value));
    } catch (final DateTimeParseException _) {
      return Optional.empty();
    }
  }

  // Leaves the note for this request (the End-User is about to sign in again), dropping the oldest
  // ones beyond the bound.
  /* package */ static void leave(final HttpServletRequest request, final Instant now) {
    final HttpSession session = request.getSession();
    final Map<String, Instant> notes = liveNotes(session, now);
    final String fingerprint = fingerprint(request);
    notes.remove(fingerprint);
    notes.put(fingerprint, now);
    final Iterator<String> oldest = notes.keySet().iterator();
    while (notes.size() > MAX_NOTES) {
      oldest.next();
      oldest.remove();
    }
    store(session, notes);
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

  // True, and the note is used up, when it was left for this very request, has not expired and the
  // End-User has signed in again since.
  /* package */ static boolean consumedByLoginSince(
      final HttpServletRequest request, final Instant latestFactorAt, final Instant now) {
    final HttpSession session = request.getSession(false);
    final Map<String, Instant> notes = liveNotes(session, now);
    final Instant leftAt = notes.get(fingerprint(request));
    if (leftAt != null && latestFactorAt.isAfter(leftAt)) {
      notes.remove(fingerprint(request));
      store(session, notes);
      return true;
    }
    return false;
  }

  // Forgets the notes that have expired; the ones still alive belong to requests that may yet be
  // resumed, so another authorize request must leave them alone.
  /* package */ static void discardExpired(final HttpServletRequest request, final Instant now) {
    final HttpSession session = request.getSession(false);
    final Map<String, Instant> stored = storedNotes(session);
    if (!stored.isEmpty()) {
      final Map<String, Instant> live = liveNotes(session, now);
      if (live.size() != stored.size()) {
        store(session, live);
      }
    }
  }
}
