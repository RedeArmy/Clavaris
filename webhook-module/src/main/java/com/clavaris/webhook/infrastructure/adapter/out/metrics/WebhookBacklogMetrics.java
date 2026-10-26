package com.clavaris.webhook.infrastructure.adapter.out.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * TD-PROC-013: the lag signals an operator needs and the application did not export. A webhook that
 * is due but unsent, and an outbox event that nobody has dispatched, are invisible failures: no
 * request fails, the consuming system simply stops hearing about changes. These gauges make "how
 * far behind are we" a number Prometheus can alert on ({@code
 * infra/observability/alert-rules.yml}).
 *
 * <ul>
 *   <li>{@code clavaris.webhook.deliveries.due}: deliveries whose next attempt time has passed;
 *   <li>{@code clavaris.webhook.deliveries.oldest_due_age_seconds}: how long the oldest has waited;
 *   <li>{@code clavaris.outbox.oldest_unpublished_age_seconds{source}}: how long the oldest outbox
 *       event of each producer has waited for the dispatcher.
 * </ul>
 *
 * <p>Reads the producers' outbox tables directly, the same data contract {@code
 * JpaOutboxEventReader} already relies on (ADR-0007 section 1), so this module still depends on
 * neither producer. The queries are served by partial indexes ({@code ix_webhook_deliveries_due},
 * the outbox unpublished indexes) and a snapshot is cached for {@link #CACHE_FOR}, so a scrape
 * every 15 seconds costs one query set, however many gauges read it. When the database cannot be
 * read the gauges report NaN rather than failing the scrape.
 */
@Component
public class WebhookBacklogMetrics implements MeterBinder {

  private static final Logger LOG = LoggerFactory.getLogger(WebhookBacklogMetrics.class);
  private static final Duration CACHE_FOR = Duration.ofSeconds(10);

  private static final String DUE_SQL =
      "SELECT count(*) FROM webhook_deliveries"
          + " WHERE status IN ('PENDING','FAILED') AND next_attempt_at <= now()";
  // The age is computed by the database (its clock, one source of truth) and is 0 when nothing
  // waits.
  private static final String DELIVERY_LAG_SQL =
      "SELECT COALESCE(EXTRACT(EPOCH FROM (now() - min(next_attempt_at))), 0)::float8"
          + " FROM webhook_deliveries"
          + " WHERE status IN ('PENDING','FAILED') AND next_attempt_at <= now()";
  private static final String IDENTITY_OUTBOX_LAG_SQL =
      "SELECT COALESCE(EXTRACT(EPOCH FROM (now() - min(occurred_at))), 0)::float8"
          + " FROM event_outbox WHERE published_at IS NULL";
  private static final String ORGANIZATION_OUTBOX_LAG_SQL =
      "SELECT COALESCE(EXTRACT(EPOCH FROM (now() - min(occurred_at))), 0)::float8"
          + " FROM organization_event_outbox WHERE published_at IS NULL";

  private final JdbcTemplate jdbc;
  private final Clock clock;

  private volatile Snapshot snapshot = Snapshot.UNAVAILABLE;
  private volatile Instant takenAt = Instant.MIN;

  // @Autowired: the second constructor exists for tests, and with two Spring needs to be told
  // which.
  @Autowired
  public WebhookBacklogMetrics(final JdbcTemplate jdbc) {
    this(jdbc, Clock.systemUTC());
  }

  /* package */ WebhookBacklogMetrics(final JdbcTemplate jdbc, final Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  @Override
  public void bindTo(final MeterRegistry registry) {
    Gauge.builder("clavaris.webhook.deliveries.due", this, m -> m.current().dueDeliveries())
        .description("Webhook deliveries whose next attempt time has passed")
        .register(registry);
    Gauge.builder(
            "clavaris.webhook.deliveries.oldest_due_age_seconds",
            this,
            m -> m.current().deliveryLagSeconds())
        .description("Seconds the oldest due webhook delivery has been waiting")
        .baseUnit("seconds")
        .register(registry);
    Gauge.builder(
            "clavaris.outbox.oldest_unpublished_age_seconds",
            this,
            m -> m.current().identityOutboxLagSeconds())
        .tag("source", "identity")
        .description("Seconds the oldest unpublished outbox event has been waiting")
        .baseUnit("seconds")
        .register(registry);
    Gauge.builder(
            "clavaris.outbox.oldest_unpublished_age_seconds",
            this,
            m -> m.current().organizationOutboxLagSeconds())
        .tag("source", "organization")
        .description("Seconds the oldest unpublished outbox event has been waiting")
        .baseUnit("seconds")
        .register(registry);
  }

  private synchronized Snapshot current() {
    final Instant now = clock.instant();
    if (Duration.between(takenAt, now).compareTo(CACHE_FOR) >= 0) {
      snapshot = read();
      takenAt = now;
    }
    return snapshot;
  }

  private Snapshot read() {
    try {
      final Long due = jdbc.queryForObject(DUE_SQL, Long.class);
      return new Snapshot(
          due == null ? 0 : due,
          lagSeconds(DELIVERY_LAG_SQL),
          lagSeconds(IDENTITY_OUTBOX_LAG_SQL),
          lagSeconds(ORGANIZATION_OUTBOX_LAG_SQL));
    } catch (final DataAccessException e) {
      // A scrape must never fail because the database could not be read; NaN says "unknown".
      LOG.warn("event=backlog_metrics_unavailable exceptionType={}", e.getClass().getName());
      return Snapshot.UNAVAILABLE;
    }
  }

  private double lagSeconds(final String sql) {
    final Double seconds = jdbc.queryForObject(sql, Double.class);
    return seconds == null ? 0.0 : Math.max(0.0, seconds);
  }

  private record Snapshot(
      double dueDeliveries,
      double deliveryLagSeconds,
      double identityOutboxLagSeconds,
      double organizationOutboxLagSeconds) {

    private static final Snapshot UNAVAILABLE =
        new Snapshot(Double.NaN, Double.NaN, Double.NaN, Double.NaN);
  }
}
