package com.clavaris.identity.domain.service;

import com.clavaris.identity.domain.model.Username;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * ADR-0031: picks a username for a brand-new social account from the one the provider offers (a
 * GitHub login). When that exact name is already taken in the Organization, a number is appended
 * ({@code juan}, {@code juan2}, {@code juan3}…), so the sign-up never ends without one only because
 * of a collision. The provider's name is never rewritten otherwise: one that is too short or has
 * characters {@link Username} does not allow yields nothing, and the person is asked to choose
 * instead.
 *
 * <p>A pure function of the offered name and a "taken" predicate, so the caller owns how
 * availability is looked up (a pre-check, not a guarantee: the unique index on {@code
 * (organization_id, username)} stays the backstop).
 */
public final class UsernameAllocator {

  /** Suffixes tried after the bare name: {@code 2} to {@code MAX_ATTEMPTS}. */
  /* package */ static final int MAX_ATTEMPTS = 20;

  private static final int MAX_LENGTH = 32;

  private UsernameAllocator() {
    // Static utility — not instantiable.
  }

  /**
   * @param offered the provider's name, possibly longer than a username may be; {@code null} or
   *     blank yields nothing
   * @param taken whether a username is already used in the Organization
   * @return the first free candidate, or empty when the name is unusable or every candidate is
   *     taken
   */
  public static Optional<Username> allocate(final String offered, final Predicate<Username> taken) {
    if (offered == null || offered.isBlank()) {
      return Optional.empty();
    }
    final String base = truncate(offered.strip().toLowerCase(java.util.Locale.ROOT), MAX_LENGTH);
    final Optional<Username> bare = tryUsername(base);
    if (bare.isEmpty()) {
      return Optional.empty();
    }
    if (!taken.test(bare.get())) {
      return bare;
    }
    for (int suffix = 2; suffix <= MAX_ATTEMPTS; suffix++) {
      final String digits = Integer.toString(suffix);
      final Optional<Username> candidate =
          tryUsername(truncate(base, MAX_LENGTH - digits.length()) + digits);
      if (candidate.isPresent() && !taken.test(candidate.get())) {
        return candidate;
      }
    }
    return Optional.empty();
  }

  private static Optional<Username> tryUsername(final String raw) {
    try {
      return Optional.of(new Username(raw));
    } catch (final IllegalArgumentException _) {
      return Optional.empty();
    }
  }

  private static String truncate(final String value, final int max) {
    return value.length() <= max ? value : value.substring(0, max);
  }
}
