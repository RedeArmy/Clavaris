package com.clavaris.webhook.application.usecase.getwebhookdeliveryactivityfororganization;

import java.time.Instant;
import java.util.List;

/**
 * Live UX request, 2026-09-25 (Clerk-parity Activity tab): a snapshot, not a true historical
 * attempt log — {@code WebhookDelivery} keeps only its own most recent attempt's outcome plus a
 * running {@code attemptCount}, not a row per individual HTTP call (see that class's own Javadoc).
 * A delivery that failed twice then succeeded therefore counts here as one success, not "two
 * failures and a success" — an honest approximation given what the domain actually records, not a
 * design flaw introduced here.
 */
// PMD.LongVariable: successfulAttempts/failedAttempts name exactly what they hold, matching
// WebhookDeliveryActivity's own class-level Javadoc terminology — same precedent this module's
// other record fields already establish.
@SuppressWarnings("PMD.LongVariable")
public record WebhookDeliveryActivity(
    long successfulAttempts, long failedAttempts, List<HourlyBucket> hourlyBuckets) {

  public record HourlyBucket(Instant hourStart, long successCount, long failureCount) {}
}
