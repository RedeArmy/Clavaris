package com.clavaris.webhook.infrastructure.adapter.out.persistence;

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

  /* package */ WebhookDeliveryRetentionJob(
      final SpringDataWebhookDeliveryJpaRepository deliveries,
      @Value("${clavaris.webhook.delivery-retention-days:90}") final int retentionDays) {
    this.deliveries = deliveries;
    this.retentionDays = retentionDays;
  }

  // Daily, off-peak (04:00 server time) — after EventOutboxRetentionJob's own 03:30 slot, same
  // "no other scheduled job to coordinate against yet, revisit cadence once real volume exists"
  // posture that job's own Javadoc already documents.
  @Scheduled(cron = "0 0 4 * * *")
  @Transactional
  /* package */ void sweepExpiredRows() {
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
