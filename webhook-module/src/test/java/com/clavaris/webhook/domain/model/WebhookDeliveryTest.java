package com.clavaris.webhook.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WebhookDeliveryTest {

  @Test
  void scheduleStartsPendingWithZeroAttemptsAndNoOutcomeYet() {
    WebhookDelivery delivery =
        WebhookDelivery.schedule(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "Account",
            UUID.randomUUID(),
            "account.created",
            "{}",
            "trace-abc123");

    assertThat(delivery.status()).isEqualTo(WebhookDeliveryStatus.PENDING);
    assertThat(delivery.attemptCount()).isZero();
    assertThat(delivery.nextAttemptAt()).isNotNull();
    assertThat(delivery.lastAttemptAt()).isNull();
    assertThat(delivery.lastResponseStatus()).isNull();
    assertThat(delivery.traceId()).isEqualTo("trace-abc123");
  }

  @Test
  void scheduleAcceptsANullTraceIdForAnUntracedOrPreMigrationSourceEvent() {
    WebhookDelivery delivery =
        WebhookDelivery.schedule(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "Account",
            UUID.randomUUID(),
            "account.created",
            "{}",
            null);

    assertThat(delivery.traceId()).isNull();
  }

  @Test
  void traceIdSurvivesEveryCopyOnWriteMutationUnchanged() {
    WebhookDelivery delivery = scheduled();

    WebhookDelivery leased = delivery.lease(Instant.now().plusSeconds(300));
    WebhookDelivery succeeded = delivery.recordSuccess(200, Instant.now());
    WebhookDelivery failed = delivery.recordFailure(500, "boom", Instant.now(), Instant.now());
    WebhookDelivery exhausted = delivery.recordFailure(500, "boom", Instant.now(), null);
    WebhookDelivery replayed = exhausted.resetForReplay(Instant.now());

    assertThat(leased.traceId())
        .isEqualTo(succeeded.traceId())
        .isEqualTo(failed.traceId())
        .isEqualTo(exhausted.traceId())
        .isEqualTo(replayed.traceId())
        .isEqualTo(delivery.traceId());
  }

  @Test
  void leaseOnlyChangesNextAttemptAtAndNothingElse() {
    WebhookDelivery delivery = scheduled();
    Instant leaseUntil = Instant.now().plusSeconds(300);

    WebhookDelivery leased = delivery.lease(leaseUntil);

    assertThat(leased.nextAttemptAt()).isEqualTo(leaseUntil);
    assertThat(leased.status()).isEqualTo(WebhookDeliveryStatus.PENDING);
    assertThat(leased.attemptCount()).isZero();
  }

  @Test
  void recordSuccessMarksSucceededClearsNextAttemptAtAndIncrementsAttemptCount() {
    WebhookDelivery delivery = scheduled();
    Instant now = Instant.now();

    WebhookDelivery succeeded = delivery.recordSuccess(200, now);

    assertThat(succeeded.status()).isEqualTo(WebhookDeliveryStatus.SUCCEEDED);
    assertThat(succeeded.attemptCount()).isEqualTo(1);
    assertThat(succeeded.nextAttemptAt()).isNull();
    assertThat(succeeded.lastAttemptAt()).isEqualTo(now);
    assertThat(succeeded.lastResponseStatus()).isEqualTo(200);
    assertThat(succeeded.lastError()).isNull();
  }

  @Test
  void recordFailureWithANextAttemptAtStaysFailedNotExhausted() {
    WebhookDelivery delivery = scheduled();
    Instant now = Instant.now();
    Instant nextAttempt = now.plusSeconds(30);

    WebhookDelivery failed = delivery.recordFailure(503, "non-2xx status 503", now, nextAttempt);

    assertThat(failed.status()).isEqualTo(WebhookDeliveryStatus.FAILED);
    assertThat(failed.attemptCount()).isEqualTo(1);
    assertThat(failed.nextAttemptAt()).isEqualTo(nextAttempt);
    assertThat(failed.lastError()).isEqualTo("non-2xx status 503");
  }

  @Test
  void recordFailureWithANullNextAttemptAtMarksExhausted() {
    WebhookDelivery delivery = scheduled();

    WebhookDelivery exhausted = delivery.recordFailure(500, "boom", Instant.now(), null);

    assertThat(exhausted.status()).isEqualTo(WebhookDeliveryStatus.EXHAUSTED);
    assertThat(exhausted.nextAttemptAt()).isNull();
  }

  @Test
  void resetForReplayGoesBackToPendingWithoutResettingTheAttemptCounter() {
    WebhookDelivery exhausted = scheduled().recordFailure(500, "boom", Instant.now(), null);

    WebhookDelivery replayed = exhausted.resetForReplay(Instant.now());

    assertThat(replayed.status()).isEqualTo(WebhookDeliveryStatus.PENDING);
    // Lifetime counter, not reset by a manual replay — see resetForReplay's own Javadoc.
    assertThat(replayed.attemptCount()).isEqualTo(exhausted.attemptCount());
    assertThat(replayed.nextAttemptAt()).isNotNull();
  }

  private WebhookDelivery scheduled() {
    return WebhookDelivery.schedule(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "Account",
        UUID.randomUUID(),
        "account.created",
        "{}",
        "trace-abc123");
  }
}
