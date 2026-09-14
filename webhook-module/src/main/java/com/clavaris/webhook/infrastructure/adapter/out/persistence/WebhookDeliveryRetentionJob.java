package com.clavaris.webhook.infrastructure.adapter.out.persistence;

import com.clavaris.common.infrastructure.adapter.out.persistence.PostgresAdvisoryJobLock;
import com.clavaris.webhook.domain.model.WebhookDeliveryStatus;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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

  private static final List<String> TERMINAL_STATUSES =
      List.of(WebhookDeliveryStatus.SUCCEEDED.name(), WebhookDeliveryStatus.EXHAUSTED.name());

  private final SpringDataWebhookDeliveryJpaRepository deliveries;
  private final int retentionDays;
  private final PostgresAdvisoryJobLock jobLock;

  /* package */ WebhookDeliveryRetentionJob(
      final SpringDataWebhookDeliveryJpaRepository deliveries,
      @Value("${clavaris.webhook.delivery-retention-days:90}") final int retentionDays,
      final PostgresAdvisoryJobLock jobLock) {
    this.deliveries = deliveries;
    this.retentionDays = retentionDays;
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
  @Transactional
  /* package */ void sweepExpiredRows() {
    jobLock.runIfLockAcquired("webhook_delivery_retention", LOG, this::sweepExpiredRowsLocked);
  }

  private void sweepExpiredRowsLocked() {
    final Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
    final long deleted = deliveries.deleteByCreatedAtBeforeAndStatusIn(cutoff, TERMINAL_STATUSES);
    if (deleted > 0) {
      LOG.info(
          "event=webhook_delivery_retention_swept deletedCount={} retentionDays={}",
          deleted,
          retentionDays);
    }
  }
}
