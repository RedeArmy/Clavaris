package com.clavaris.identity.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** TD-PERF-040: the new-device notification queue is bounded. */
class KnownDeviceUseCaseConfigTest {

  @Test
  void rejectsWorkBeyondTheQueueCapacityInsteadOfQueueingWithoutLimit() throws Exception {
    final ExecutorService executor =
        new KnownDeviceUseCaseConfig().newDeviceNotificationExecutor(1, 2);
    final CountDownLatch release = new CountDownLatch(1);
    final CountDownLatch started = new CountDownLatch(1);
    try {
      executor.execute(
          () -> {
            started.countDown();
            awaitQuietly(release);
          });
      assertThat(started.await(5, TimeUnit.SECONDS)).as("the one worker is busy").isTrue();
      executor.execute(() -> awaitQuietly(release));
      executor.execute(() -> awaitQuietly(release));

      assertThatThrownBy(() -> executor.execute(() -> {}))
          .as("a fourth task: one running, two queued, no room left")
          .isInstanceOf(RejectedExecutionException.class);
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
  }

  private static void awaitQuietly(final CountDownLatch latch) {
    try {
      latch.await(10, TimeUnit.SECONDS);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
