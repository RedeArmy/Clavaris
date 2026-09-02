package com.clavaris.webhook.domain.service;

import java.time.Duration;

/**
 * ADR-0007 §2's own "exponential backoff + jitter, capped at a fixed number of attempts over a
 * bounded window" — the pure math half. {@code maxAttempts} itself is deliberately not here: how
 * many attempts is too many is an operational value ({@code DeliverPendingWebhooksService}'s own
 * {@code @Value}), same reasoning {@code WebhookEndpoint.rotateSecret}'s overlap-window parameter
 * already establishes for this module. Pure function, no I/O, no framework — {@code jitterFactor}
 * is supplied by the caller (typically {@code ThreadLocalRandom}) rather than this class rolling
 * its own randomness, so the math itself stays deterministic and trivially testable.
 */
@SuppressWarnings("PMD.LongVariable")
public final class WebhookRetrySchedule {

  // 30s doubling — attempt 1 waits 30s, attempt 2 waits 1m, ... same order of magnitude as ADR-0007
  // §2's own "8 attempts over 24h" example schedule (30s * 2^7 = 64m, well inside a 24h window with
  // room for the cap below to matter on later attempts).
  private static final long BASE_DELAY_SECONDS = 30L;

  // Caps growth at 1 hour per attempt — without a cap, a naive 2^n schedule would make each
  // subsequent attempt in an 8-attempt run take dramatically longer than the last for no real
  // benefit; a 1h ceiling keeps the tail of the schedule from ballooning into days.
  private static final long MAX_DELAY_SECONDS = 3600L;

  // attemptNumber is 1-based (bit-shifting by more than 10 would already be past the cap above),
  // so anything past this is clamped rather than risking an overflow on the shift itself.
  private static final int MAX_SHIFT = 10;

  private WebhookRetrySchedule() {}

  /**
   * @param attemptNumber the 1-based attempt that just failed (the delay returned is until the next
   *     one).
   * @param jitterFactor a multiplier, typically in {@code [0.8, 1.2]} — spreads out retries from
   *     many endpoints that all failed at the same moment (e.g. a shared downstream outage) so they
   *     don't all retry in lockstep.
   */
  public static Duration nextDelay(final int attemptNumber, final double jitterFactor) {
    final int shift = Math.clamp((long) attemptNumber - 1, 0, MAX_SHIFT);
    final long uncappedSeconds = BASE_DELAY_SECONDS * (1L << shift);
    final long cappedSeconds = Math.min(uncappedSeconds, MAX_DELAY_SECONDS);
    final long jitteredSeconds = Math.round(cappedSeconds * jitterFactor);
    return Duration.ofSeconds(Math.max(1, jitteredSeconds));
  }
}
