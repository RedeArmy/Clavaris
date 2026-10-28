package com.clavaris.identity.infrastructure.adapter.in.web;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * TD-SEC-072: carries how a session was authenticated across the one place that has to rebuild it.
 * Connecting a provider sends the browser through the OAuth2 login filter, which replaces the
 * security context; the Account's session is restored afterwards, and without this it would be
 * restored as a fresh social sign-in (a new {@code amr} and a new authentication time that a
 * relying party checking {@code acr}, {@code amr} or {@code max_age} would take for a new login).
 *
 * <p>A factor is written as {@code authority|issuedAtMillis} so the original authentication time
 * survives. Only three kinds of authority can come back — the tenant tier marker, {@code FACTOR_*}
 * and {@code AMR_*} — so a corrupted or tampered session attribute can never restore a role it was
 * not given by a real sign-in.
 */
public final class SessionFactors {

  private static final String TIER_MARKER = "ROLE_ACCOUNT";
  private static final String FACTOR_PREFIX = "FACTOR_";
  private static final String AMR_PREFIX = "AMR_";
  private static final char SEPARATOR = '|';

  private SessionFactors() {
    // Utility class — never instantiated.
  }

  /**
   * The restorable authorities of {@code authentication}, as plain Strings safe to keep in JSON.
   */
  public static List<String> capture(final Authentication authentication) {
    final List<String> factors = new ArrayList<>();
    if (authentication == null) {
      return factors;
    }
    for (final GrantedAuthority authority : authentication.getAuthorities()) {
      final String name = authority.getAuthority();
      if (name == null || !allowed(name)) {
        continue;
      }
      final Instant issuedAt =
          authority instanceof FactorGrantedAuthority factor ? factor.getIssuedAt() : null;
      factors.add(name + SEPARATOR + (issuedAt == null ? 0 : issuedAt.toEpochMilli()));
    }
    return factors;
  }

  /** Rebuilds the authorities; an entry that is malformed or not allowed is dropped. */
  public static List<GrantedAuthority> restore(final List<?> specs) {
    final List<GrantedAuthority> authorities = new ArrayList<>();
    if (specs != null) {
      for (final Object spec : specs) {
        if (spec instanceof String text) {
          toAuthority(text).ifPresent(authorities::add);
        }
      }
    }
    return authorities;
  }

  private static Optional<GrantedAuthority> toAuthority(final String text) {
    final int cut = text.lastIndexOf(SEPARATOR);
    final String name = cut < 0 ? text : text.substring(0, cut);
    if (!allowed(name)) {
      return Optional.empty();
    }
    if (!name.startsWith(FACTOR_PREFIX)) {
      return Optional.of(new SimpleGrantedAuthority(name));
    }
    final long issuedAt = cut < 0 ? 0 : parseMillis(text.substring(cut + 1));
    return Optional.of(
        FactorGrantedAuthority.withAuthority(name)
            .issuedAt(issuedAt > 0 ? Instant.ofEpochMilli(issuedAt) : Instant.now())
            .build());
  }

  /**
   * True if the session's primary sign-in happened within {@code window}. The time is the one the
   * factors carry (preserved across the connect-a-provider round trip, TD-SEC-072), not the age of
   * the HTTP session, which can predate the sign-in by however long the login page sat open.
   */
  public static boolean signedInWithin(final Authentication authentication, final Duration window) {
    if (authentication == null) {
      return false;
    }
    final Instant cutoff = Instant.now().minus(window);
    for (final GrantedAuthority authority : authentication.getAuthorities()) {
      if (authority instanceof FactorGrantedAuthority factor
          && factor.getIssuedAt().isAfter(cutoff)) {
        return true;
      }
    }
    return false;
  }

  private static boolean allowed(final String name) {
    return TIER_MARKER.equals(name)
        || name.startsWith(FACTOR_PREFIX)
        || name.startsWith(AMR_PREFIX);
  }

  private static long parseMillis(final String text) {
    try {
      return Long.parseLong(text);
    } catch (final NumberFormatException _) {
      return 0;
    }
  }
}
