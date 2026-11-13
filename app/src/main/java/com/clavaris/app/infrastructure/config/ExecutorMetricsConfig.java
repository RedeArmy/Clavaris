package com.clavaris.app.infrastructure.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Puts every pool this application starts itself on {@code /actuator/prometheus}: queue depth, room
 * left in the queue, active threads, pool size and completed tasks, tagged with the bean's name
 * ({@code email-request}, {@code webhookDeliveryExecutor}, ...).
 *
 * <p>Spring Boot instruments its own {@code ThreadPoolTaskExecutor} beans and nothing else, so the
 * three bounded pools written here ({@code emailRequestExecutor}, {@code
 * newDeviceNotificationExecutor}, {@code webhookDeliveryExecutor}) were invisible: they drop work
 * once their queue is full (that is the point of bounding them), and the only trace was a WARN
 * line. A mail-provider outage fills the email pool without anything failing, so the first signal
 * has to be the queue itself. See the {@code ExecutorQueueFull} alert.
 */
@Configuration
class ExecutorMetricsConfig {

  // Written out for the same reason as every config class here: only Spring's component scan
  // instantiates it.
  /* package */ ExecutorMetricsConfig() {
    // Intentionally empty — this class holds no state, only the @Bean method below.
  }

  // After every singleton exists, so each pool is a finished bean; the registry is optional so a
  // build without the metrics starter still starts.
  @Bean
  /* package */ SmartInitializingSingleton boundExecutorMetrics(
      final ObjectProvider<MeterRegistry> registry, final Map<String, ExecutorService> executors) {
    return () ->
        registry.ifAvailable(
            meterRegistry ->
                executors.forEach(
                    (name, executor) ->
                        new ExecutorServiceMetrics(executor, name, List.<Tag>of())
                            .bindTo(meterRegistry)));
  }
}
