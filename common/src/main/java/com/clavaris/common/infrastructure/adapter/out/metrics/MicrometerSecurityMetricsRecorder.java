package com.clavaris.common.infrastructure.adapter.out.metrics;

import com.clavaris.common.application.port.SecurityMetricsRecorder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * TD-FUT-011 / ADR-0015: the one real implementation of {@link SecurityMetricsRecorder} — every
 * caller across every module goes through this. {@link MeterRegistry} is injected, not constructed
 * here: the real, autoconfigured registry (a {@code PrometheusMeterRegistry} once {@code
 * micrometer-registry-prometheus} is on {@code app}'s own classpath) is wired by Spring Boot's own
 * Actuator autoconfiguration, this class only ever depends on the interface.
 */
@Component
class MicrometerSecurityMetricsRecorder implements SecurityMetricsRecorder {

  private final MeterRegistry registry;

  // Constructed only by Spring's own component scan.
  /* package */ MicrometerSecurityMetricsRecorder(final MeterRegistry registry) {
    this.registry = registry;
  }

  @Override
  public void increment(final String metricName, final String... tagKeyValuePairs) {
    Counter.builder(metricName).tags(tagKeyValuePairs).register(registry).increment();
  }
}
