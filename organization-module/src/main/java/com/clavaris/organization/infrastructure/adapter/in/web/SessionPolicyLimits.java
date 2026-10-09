package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.organization.domain.model.SessionPolicy;

/**
 * The bounds of the three session durations, in minutes, for the Sessions page. The page hands them
 * to its script as data attributes, so the live check in the browser and the check on the server
 * state the same limits, and neither repeats the numbers.
 */
public record SessionPolicyLimits(
    int minimumLifetime,
    int maximumLifetime,
    int minimumInactivity,
    int maximumInactivity,
    int minimumWindow,
    int maximumWindow) {

  /** The limits the domain enforces today. */
  public static SessionPolicyLimits current() {
    return new SessionPolicyLimits(
        SessionPolicy.MIN_MAXIMUM_LIFETIME_MINUTES,
        SessionPolicy.MAX_MAXIMUM_LIFETIME_MINUTES,
        SessionPolicy.MIN_INACTIVITY_TIMEOUT_MINUTES,
        SessionPolicy.MAX_INACTIVITY_TIMEOUT_MINUTES,
        SessionPolicy.MIN_REVERIFICATION_WINDOW_MINUTES,
        SessionPolicy.MAX_REVERIFICATION_WINDOW_MINUTES);
  }
}
