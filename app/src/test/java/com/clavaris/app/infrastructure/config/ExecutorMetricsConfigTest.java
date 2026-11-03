package com.clavaris.app.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * The pools this application starts itself are on the metrics endpoint, each under its own name.
 */
class ExecutorMetricsConfigTest {

  @Test
  void everyPoolIsBoundUnderItsBeanName() {
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final StaticListableBeanFactory factory = new StaticListableBeanFactory();
    factory.addBean("meterRegistry", registry);
    final ExecutorService email = Executors.newFixedThreadPool(1);
    final ExecutorService webhooks = Executors.newFixedThreadPool(1);
    try {
      new ExecutorMetricsConfig()
          .boundExecutorMetrics(
              factory.getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class),
              Map.of("emailRequestExecutor", email, "webhookDeliveryExecutor", webhooks))
          .afterSingletonsInstantiated();

      assertThat(registry.find("executor.queued").tag("name", "emailRequestExecutor").gauge())
          .isNotNull();
      assertThat(
              registry
                  .find("executor.queue.remaining")
                  .tag("name", "webhookDeliveryExecutor")
                  .gauge())
          .isNotNull();
      assertThat(registry.find("executor.active").gauges()).hasSize(2);
    } finally {
      email.shutdownNow();
      webhooks.shutdownNow();
    }
  }

  @Test
  void withoutAMeterRegistryNothingFails() {
    final StaticListableBeanFactory none = new StaticListableBeanFactory();
    final ExecutorService executor = Executors.newFixedThreadPool(1);
    try {
      final SmartInitializingSingleton binder =
          new ExecutorMetricsConfig()
              .boundExecutorMetrics(
                  none.getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class),
                  Map.of("emailRequestExecutor", executor));

      assertThatNoException().isThrownBy(binder::afterSingletonsInstantiated);
    } finally {
      executor.shutdownNow();
    }
  }
}
