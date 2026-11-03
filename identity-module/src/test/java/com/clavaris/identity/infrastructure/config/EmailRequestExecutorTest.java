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

  private final PasswordlessAndSocialUseCaseConfig config =
      new PasswordlessAndSocialUseCaseConfig();

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

  private static void await(final CountDownLatch latch) {
    try {
      latch.await();
    } catch (final InterruptedException _) {
      Thread.currentThread().interrupt();
    }
  }
}
