package com.clavaris.webhook.application.usecase.getwebhookdeliveryactivityfororganization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clavaris.webhook.application.usecase.deliverpendingwebhooks.WebhookDeliveryRepository;
import com.clavaris.webhook.domain.model.WebhookDelivery;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GetWebhookDeliveryActivityForOrganizationServiceTest {

  private final WebhookDeliveryRepository deliveries = mock(WebhookDeliveryRepository.class);
  private final GetWebhookDeliveryActivityForOrganizationService service =
      new GetWebhookDeliveryActivityForOrganizationService(deliveries);

  private WebhookDelivery deliveryAttemptedAt(final Instant lastAttemptAt, final String status) {
    return WebhookDelivery.reconstitute(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "Account",
        UUID.randomUUID(),
        "account.created",
        "{}",
        null,
        com.clavaris.webhook.domain.model.WebhookDeliveryStatus.valueOf(status),
        1,
        null,
        lastAttemptAt,
        status.equals("SUCCEEDED") ? 200 : 500,
        status.equals("SUCCEEDED") ? null : "boom",
        lastAttemptAt);
  }

  @Test
  void countsAttemptsInTheCurrentHourAsSuccessfulOrFailed() {
    UUID organizationId = UUID.randomUUID();
    Instant currentHour = Instant.now().truncatedTo(ChronoUnit.HOURS);
    WebhookDelivery succeeded = deliveryAttemptedAt(currentHour, "SUCCEEDED");
    WebhookDelivery failed = deliveryAttemptedAt(currentHour, "FAILED");
    WebhookDelivery exhausted = deliveryAttemptedAt(currentHour, "EXHAUSTED");
    when(deliveries.findAllByOrganizationIdWithAttemptSince(any(), any()))
        .thenReturn(List.of(succeeded, failed, exhausted));

    WebhookDeliveryActivity activity =
        service.handle(new GetWebhookDeliveryActivityForOrganizationQuery(organizationId));

    assertThat(activity.successfulAttempts()).isEqualTo(1);
    assertThat(activity.failedAttempts()).isEqualTo(2);
  }

  @Test
  void returnsSixHourlyBucketsEvenWhenThereAreNoAttempts() {
    when(deliveries.findAllByOrganizationIdWithAttemptSince(any(), any())).thenReturn(List.of());

    WebhookDeliveryActivity activity =
        service.handle(new GetWebhookDeliveryActivityForOrganizationQuery(UUID.randomUUID()));

    assertThat(activity.hourlyBuckets()).hasSize(6);
    assertThat(activity.successfulAttempts()).isZero();
    assertThat(activity.failedAttempts()).isZero();
    activity
        .hourlyBuckets()
        .forEach(
            bucket -> {
              assertThat(bucket.successCount()).isZero();
              assertThat(bucket.failureCount()).isZero();
            });
  }

  @Test
  void hourlyBucketsAreOrderedOldestFirst() {
    when(deliveries.findAllByOrganizationIdWithAttemptSince(any(), any())).thenReturn(List.of());

    WebhookDeliveryActivity activity =
        service.handle(new GetWebhookDeliveryActivityForOrganizationQuery(UUID.randomUUID()));

    assertThat(activity.hourlyBuckets())
        .isSortedAccordingTo((a, b) -> a.hourStart().compareTo(b.hourStart()));
  }
}
