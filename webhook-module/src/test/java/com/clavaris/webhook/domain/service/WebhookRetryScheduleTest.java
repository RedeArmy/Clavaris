package com.clavaris.webhook.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class WebhookRetryScheduleTest {

  @Test
  void firstAttemptWaitsAboutTheBaseDelay() {
    Duration delay = WebhookRetrySchedule.nextDelay(1, 1.0);

    assertThat(delay).isEqualTo(Duration.ofSeconds(30));
  }

  @Test
  void delayDoublesWithEachSubsequentAttempt() {
    assertThat(WebhookRetrySchedule.nextDelay(2, 1.0)).isEqualTo(Duration.ofSeconds(60));
    assertThat(WebhookRetrySchedule.nextDelay(3, 1.0)).isEqualTo(Duration.ofSeconds(120));
    assertThat(WebhookRetrySchedule.nextDelay(4, 1.0)).isEqualTo(Duration.ofSeconds(240));
  }

  @Test
  void delayIsCappedRatherThanGrowingUnbounded() {
    Duration delay = WebhookRetrySchedule.nextDelay(20, 1.0);

    assertThat(delay).isEqualTo(Duration.ofHours(1));
  }

  @Test
  void jitterFactorScalesTheDelayProportionally() {
    Duration wider = WebhookRetrySchedule.nextDelay(1, 1.2);
    Duration narrower = WebhookRetrySchedule.nextDelay(1, 0.8);

    assertThat(wider).isEqualTo(Duration.ofSeconds(36));
    assertThat(narrower).isEqualTo(Duration.ofSeconds(24));
  }

  @Test
  void neverReturnsAZeroOrNegativeDelayEvenWithATinyJitterFactor() {
    Duration delay = WebhookRetrySchedule.nextDelay(1, 0.0);

    assertThat(delay).isPositive();
  }
}
