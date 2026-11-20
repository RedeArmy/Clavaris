package com.clavaris.clientregistry.domain.model;

import com.clavaris.common.domain.model.InvalidInputException;

/**
 * TD-SEC-104 / ADR-0049: how long an access token issued to one client lives. Until now it was
 * Spring Authorization Server's default for every client of every Organization, and nothing could
 * change it. It is also the bound of what revocation cannot do: a token issued before a revocation
 * or a logout stays valid until it expires (ADR-0044 §1), so a client that wants a shorter window
 * can now ask for one, and one that wants fewer refreshes a longer one.
 *
 * <p>BR-ID-36. Absent means the default, so every existing client behaves exactly as before. The
 * bounds are a hard system cap and not a tenant's to move: a minute is the shortest that survives a
 * token request and a retry, and an hour is the longest a revocation is allowed to leave a stolen
 * token usable.
 */
public final class AccessTokenLifetime {

  public static final int DEFAULT_SECONDS = 300;
  public static final int MIN_SECONDS = 60;
  public static final int MAX_SECONDS = 3_600;

  private AccessTokenLifetime() {
    // Static helpers only.
  }

  /** Null (the default) is allowed; anything else must be inside the bounds. */
  public static Integer requireAllowed(final Integer seconds) {
    if (seconds != null && (seconds < MIN_SECONDS || seconds > MAX_SECONDS)) {
      throw new InvalidInputException(
          "accessTokenTtlSeconds ("
              + seconds
              + ") must be between "
              + MIN_SECONDS
              + " and "
              + MAX_SECONDS
              + " seconds");
    }
    return seconds;
  }

  public static int effectiveSeconds(final Integer seconds) {
    return seconds == null ? DEFAULT_SECONDS : seconds;
  }
}
