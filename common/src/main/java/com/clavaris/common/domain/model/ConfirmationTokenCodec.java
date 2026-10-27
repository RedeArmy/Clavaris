package com.clavaris.common.domain.model;

import java.time.Instant;

/**
 * The value a two-step destructive confirmation (delete an Organization, delete an OAuth client)
 * keeps in the session: the single-use token and its expiry joined into one String (TD-ARCH-039: a
 * plain String lets the Redis session serializer store it as JSON without a type of ours on its
 * allow-list). Shared so the two modules that hold such a token do not each carry a copy of the
 * parsing; the session handling around it stays with each module, which owns its own attribute
 * names. The token is a UUID, so the separator cannot occur in it.
 */
public final class ConfirmationTokenCodec {

  private static final char SEPARATOR = '|';

  private ConfirmationTokenCodec() {
    // Utility class — never instantiated.
  }

  public static String encode(final String token, final Instant expiresAt) {
    return token + SEPARATOR + expiresAt.toEpochMilli();
  }

  /** True only for a well-formed value that has not expired and carries exactly this token. */
  public static boolean matches(
      final String stored, final String submittedToken, final Instant now) {
    final int separator = stored.indexOf(SEPARATOR);
    if (separator < 0) {
      return false;
    }
    final long expiresAtMillis;
    try {
      expiresAtMillis = Long.parseLong(stored.substring(separator + 1));
    } catch (final NumberFormatException _) {
      return false;
    }
    return !now.isAfter(Instant.ofEpochMilli(expiresAtMillis))
        && stored.substring(0, separator).equals(submittedToken);
  }
}
