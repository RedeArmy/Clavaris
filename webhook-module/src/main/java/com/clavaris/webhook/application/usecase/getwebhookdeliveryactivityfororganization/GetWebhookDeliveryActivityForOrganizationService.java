package com.clavaris.webhook.application.usecase.getwebhookdeliveryactivityfororganization;

import com.clavaris.webhook.application.usecase.deliverpendingwebhooks.WebhookDeliveryRepository;
import com.clavaris.webhook.application.usecase.getwebhookdeliveryactivityfororganization.WebhookDeliveryActivity.HourlyBucket;
import com.clavaris.webhook.domain.model.WebhookDelivery;
import com.clavaris.webhook.domain.model.WebhookDeliveryStatus;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Live UX request, 2026-09-25 (Clerk-parity Activity tab, "Last 6 hours" window — matching the live
 * reference screenshot). Buckets every attempt with a {@code lastAttemptAt} inside the window into
 * its own hour, success vs. failure ({@link WebhookDeliveryStatus#SUCCEEDED} vs. {@code
 * FAILED}/{@code EXHAUSTED}) — a delivery still {@code PENDING} with no attempt yet has a {@code
 * null} {@code lastAttemptAt} and is naturally excluded by {@link
 * WebhookDeliveryRepository#findAllByOrganizationIdWithAttemptSince}'s own query. Every hour in the
 * window is pre-seeded at zero so a quiet hour renders as an empty bar, not a missing one.
 */
public class GetWebhookDeliveryActivityForOrganizationService
    implements GetWebhookDeliveryActivityForOrganizationUseCase {

  // Matches Clerk's own "Last 6 hours" Activity window (this class's own Javadoc).
  private static final int WINDOW_HOURS = 6;

  private final WebhookDeliveryRepository deliveries;

  public GetWebhookDeliveryActivityForOrganizationService(
      final WebhookDeliveryRepository deliveries) {
    this.deliveries = deliveries;
  }

  @Override
  public WebhookDeliveryActivity handle(
      final GetWebhookDeliveryActivityForOrganizationQuery query) {
    final Instant nowHour = Instant.now().truncatedTo(ChronoUnit.HOURS);
    final Instant windowStart = nowHour.minus(Duration.ofHours(WINDOW_HOURS - 1L));

    final Map<Instant, long[]> countsByHour = new TreeMap<>();
    for (Instant hour = windowStart;
        !hour.isAfter(nowHour);
        hour = hour.plus(Duration.ofHours(1))) {
      countsByHour.put(hour, new long[2]);
    }

    final List<WebhookDelivery> attempts =
        deliveries.findAllByOrganizationIdWithAttemptSince(query.organizationId(), windowStart);

    long totalSuccess = 0;
    long totalFailure = 0;
    for (final WebhookDelivery attempt : attempts) {
      final Instant hour = attempt.lastAttemptAt().truncatedTo(ChronoUnit.HOURS);
      final long[] counts = countsByHour.computeIfAbsent(hour, ignored -> new long[2]);
      if (attempt.status() == WebhookDeliveryStatus.SUCCEEDED) {
        counts[0]++;
        totalSuccess++;
      } else if (attempt.status() == WebhookDeliveryStatus.FAILED
          || attempt.status() == WebhookDeliveryStatus.EXHAUSTED) {
        counts[1]++;
        totalFailure++;
      }
    }

    final List<HourlyBucket> hourlyBuckets =
        countsByHour.entrySet().stream()
            .map(
                entry -> new HourlyBucket(entry.getKey(), entry.getValue()[0], entry.getValue()[1]))
            .toList();

    return new WebhookDeliveryActivity(totalSuccess, totalFailure, hourlyBuckets);
  }
}
