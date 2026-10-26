package com.clavaris.webhook.infrastructure.adapter.out.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

/** TD-PROC-013: the lag gauges report real backlog, zero when idle, and NaN when unreadable. */
class WebhookBacklogMetricsTest {

  private static final Instant NOW = Instant.parse("2026-10-25T12:00:00Z");

  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

  private WebhookBacklogMetrics bind(final Clock clock) {
    final WebhookBacklogMetrics metrics = new WebhookBacklogMetrics(jdbc, clock);
    metrics.bindTo(registry);
    return metrics;
  }

  @Test
  void reportsTheDueCountAndTheAgeOfTheOldestWaitingItem() {
    when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(7L);
    when(jdbc.queryForObject(anyString(), eq(Double.class))).thenReturn(90.0);
    bind(Clock.fixed(NOW, ZoneOffset.UTC));

    assertThat(registry.get("clavaris.webhook.deliveries.due").gauge().value()).isEqualTo(7.0);
    assertThat(registry.get("clavaris.webhook.deliveries.oldest_due_age_seconds").gauge().value())
        .isEqualTo(90.0);
    assertThat(
            registry
                .get("clavaris.outbox.oldest_unpublished_age_seconds")
                .tag("source", "identity")
                .gauge()
                .value())
        .isEqualTo(90.0);
  }

  @Test
  void nothingWaitingIsZeroLagNotUnknown() {
    when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(0L);
    when(jdbc.queryForObject(anyString(), eq(Double.class))).thenReturn(null);
    bind(Clock.fixed(NOW, ZoneOffset.UTC));

    assertThat(registry.get("clavaris.webhook.deliveries.due").gauge().value()).isZero();
    assertThat(
            registry
                .get("clavaris.outbox.oldest_unpublished_age_seconds")
                .tag("source", "organization")
                .gauge()
                .value())
        .isZero();
  }

  @Test
  void anUnreadableDatabaseGivesNaNInsteadOfFailingTheScrape() {
    when(jdbc.queryForObject(anyString(), eq(Long.class)))
        .thenThrow(new DataAccessResourceFailureException("down"));
    bind(Clock.fixed(NOW, ZoneOffset.UTC));

    assertThat(registry.get("clavaris.webhook.deliveries.due").gauge().value()).isNaN();
  }

  @Test
  void aSnapshotIsReusedWithinTheCacheWindowSoOneScrapeIsOneQuerySet() {
    when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(1L);
    when(jdbc.queryForObject(anyString(), eq(Double.class))).thenReturn(null);
    bind(Clock.fixed(NOW, ZoneOffset.UTC));

    registry.get("clavaris.webhook.deliveries.due").gauge().value();
    registry.get("clavaris.webhook.deliveries.oldest_due_age_seconds").gauge().value();
    registry
        .get("clavaris.outbox.oldest_unpublished_age_seconds")
        .tag("source", "identity")
        .gauge()
        .value();

    verify(jdbc, times(1)).queryForObject(anyString(), eq(Long.class));
  }
}
