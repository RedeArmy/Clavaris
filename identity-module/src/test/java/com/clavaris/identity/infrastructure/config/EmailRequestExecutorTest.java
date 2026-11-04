package com.clavaris.identity.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * The email pool drops work once its queue is full, on purpose; what it must not do is drop it
 * silently. A rejection is counted, so an outage that fills the queue is an alert and not a WARN
 * line nobody reads.
 */
class EmailRequestExecutorTest {

  private final EmailPoolsConfig config = new EmailPoolsConfig();

  private static ObjectProvider<MeterRegistry> providing(final MeterRegistry registry) {
    final StaticListableBeanFactory factory = new StaticListableBeanFactory();
    factory.addBean("meterRegistry", registry);
    return factory.getBeanProvider(MeterRegistry.class);
  }

  @Test
  void aTaskThatDoesNotFitIsRejectedAndCounted() {
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final ExecutorService executor = config.emailRequestExecutor(1, 1, providing(registry));
    final CountDownLatch release = new CountDownLatch(1);
    try {
      // One task runs and blocks, one waits in the queue: the pool is full.
      executor.execute(() -> await(release));
      executor.execute(() -> await(release));

      assertThatExceptionOfType(RejectedExecutionException.class)
          .isThrownBy(() -> executor.execute(() -> {}));
      assertThatExceptionOfType(RejectedExecutionException.class)
          .isThrownBy(() -> executor.execute(() -> {}));

      assertThat(registry.counter("clavaris.email.request.rejected").count()).isEqualTo(2.0);
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void withoutAMeterRegistryItStillRejectsAndDoesNotFail() {
    final StaticListableBeanFactory none = new StaticListableBeanFactory();
    final ExecutorService executor =
        config.emailRequestExecutor(1, 1, none.getBeanProvider(MeterRegistry.class));
    final CountDownLatch release = new CountDownLatch(1);
    try {
      executor.execute(() -> await(release));
      executor.execute(() -> await(release));

      assertThatExceptionOfType(RejectedExecutionException.class)
          .isThrownBy(() -> executor.execute(() -> {}));
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
  }

  // TD-PERF-054: the mail that gets a person back into their account has a pool of its own, counted
  // under its own metric, so a flood of sign-in code requests can neither delay nor push it out.
  @Test
  void theRecoveryPoolIsCountedUnderItsOwnMetric() {
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final ExecutorService recovery = config.recoveryEmailExecutor(1, 1, providing(registry));
    final CountDownLatch release = new CountDownLatch(1);
    try {
      recovery.execute(() -> await(release));
      recovery.execute(() -> await(release));

      assertThatExceptionOfType(RejectedExecutionException.class)
          .isThrownBy(() -> recovery.execute(() -> {}));

      assertThat(registry.counter("clavaris.email.recovery.rejected").count()).isEqualTo(1.0);
      assertThat(registry.counter("clavaris.email.request.rejected").count()).isZero();
    } finally {
      release.countDown();
      recovery.shutdownNow();
    }
  }

  @Test
  void aFullSignInPoolLeavesTheRecoveryPoolFree() throws Exception {
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final ExecutorService signIn = config.emailRequestExecutor(1, 1, providing(registry));
    final ExecutorService recovery = config.recoveryEmailExecutor(1, 1, providing(registry));
    final CountDownLatch release = new CountDownLatch(1);
    final CountDownLatch ran = new CountDownLatch(1);
    try {
      signIn.execute(() -> await(release));
      signIn.execute(() -> await(release));
      assertThatExceptionOfType(RejectedExecutionException.class)
          .isThrownBy(() -> signIn.execute(() -> {}));

      recovery.execute(ran::countDown);

      assertThat(ran.await(5, java.util.concurrent.TimeUnit.SECONDS))
          .as("a reset still goes out while the sign-in pool is saturated")
          .isTrue();
    } finally {
      release.countDown();
      signIn.shutdownNow();
      recovery.shutdownNow();
    }
  }

  private static void await(final CountDownLatch latch) {
    try {
      latch.await();
    } catch (final InterruptedException _) {
      Thread.currentThread().interrupt();
    }
  }
}
