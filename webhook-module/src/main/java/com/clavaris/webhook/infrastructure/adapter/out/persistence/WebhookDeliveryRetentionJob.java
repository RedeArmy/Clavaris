package com.clavaris.webhook.infrastructure.adapter.out.persistence;

import com.clavaris.common.infrastructure.adapter.out.persistence.PostgresAdvisoryJobLock;
import com.clavaris.webhook.domain.model.WebhookDeliveryStatus;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * ADR-0007's own second open question (delivery log retention window), resolved: terminal rows
 * ({@code SUCCEEDED}/{@code EXHAUSTED}) older than {@code retentionDays} are swept — {@code
 * PENDING}/{@code FAILED} rows are never touched by age alone, since a {@code FAILED} row's own
 * {@code nextAttemptAt} may still be legitimately in the future. Same shape as identity-module's
 * own {@code EventOutboxRetentionJob}, applied to this module's own table.
 */
@Component
class WebhookDeliveryRetentionJob {

  private static final Logger LOG = LoggerFactory.getLogger(WebhookDeliveryRetentionJob.class);

  // The two terminal statuses as literals in the text, taken from the enum and never from input, so
  // that the planner can prove the predicate implies ix_webhook_deliveries_terminal_created_at's.
  /* package */ static final String DELETE_OLDEST_TERMINAL_SQL =
      "DELETE FROM webhook_deliveries WHERE id IN (SELECT id FROM webhook_deliveries WHERE status IN ('"
          + WebhookDeliveryStatus.SUCCEEDED.name()
          + "', '"
          + WebhookDeliveryStatus.EXHAUSTED.name()
          + "') AND created_at < ? ORDER BY created_at LIMIT ?)";

  private final JdbcTemplate jdbc;
  private final int retentionDays;
  private final int batchSize;
  private final PostgresAdvisoryJobLock jobLock;

  /* package */ WebhookDeliveryRetentionJob(
      final JdbcTemplate jdbc,
      @Value("${clavaris.webhook.delivery-retention-days:90}") final int retentionDays,
      @Value("${clavaris.webhook.delivery-retention-batch-size:5000}") final int batchSize,
      final PostgresAdvisoryJobLock jobLock) {
    this.jdbc = jdbc;
    this.retentionDays = retentionDays;
    this.batchSize = batchSize;
    this.jobLock = jobLock;
  }

  // Daily, off-peak (04:00 server time) — after EventOutboxRetentionJob's own 03:30 slot, same
  // "no other scheduled job to coordinate against yet, revisit cadence once real volume exists"
  // posture that job's own Javadoc already documents.
  //
  // TD-FUT-033: guarded by PostgresAdvisoryJobLock — distinct from WebhookDispatchScheduler's own
  // two ticks (already safe via SELECT ... FOR UPDATE SKIP LOCKED, a different mechanism entirely
  // — this job needed its own guard because a plain DELETE has no row to lock against). See
  // KnownDeviceRetentionJob's own identical shape for why @Transactional stays on this
  // externally-invoked method, not the private one it delegates to (Spring AOP self-invocation).
  @Scheduled(cron = "0 0 4 * * *")
  /* package */ void sweepExpiredRows() {
    jobLock.runIfLockAcquired("webhook_delivery_retention", LOG, this::sweepExpiredRowsLocked);
  }

  // TD-PERF-057: the sweep used a Spring Data derived delete, which loads every matching row into
  // the persistence context and removes it one at a time, all inside one transaction. On the table
  // that grows fastest that is an out-of-memory and a long lock waiting for a bad night. Now each
  // batch is a single set-based DELETE in its own short transaction (no @Transactional around the
  // loop: that would bring the one long transaction back), so a crash keeps what was already swept.
  /* package */ void sweepExpiredRowsLocked() {
    final OffsetDateTime cutoff =
        OffsetDateTime.ofInstant(
            Instant.now().minus(retentionDays, ChronoUnit.DAYS), ZoneOffset.UTC);
    long deleted = 0;
    int batch;
    do {
      batch = jdbc.update(DELETE_OLDEST_TERMINAL_SQL, cutoff, batchSize);
      deleted += batch;
    } while (batch == batchSize);
    if (deleted > 0) {
      LOG.info(
          "event=webhook_delivery_retention_swept deletedCount={} retentionDays={}",
          deleted,
          retentionDays);
    }
  }
}
