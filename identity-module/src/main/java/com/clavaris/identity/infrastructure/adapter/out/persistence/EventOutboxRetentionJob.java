package com.clavaris.identity.infrastructure.adapter.out.persistence;

import com.clavaris.common.infrastructure.adapter.out.persistence.EventOutboxRetentionSweeper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * TD-TEST-002: bounds {@code event_outbox} growth with an age-based retention sweep — the table had
 * no cleanup job at all before this, and every {@code AccountRegisteredEvent}/etc. written by a
 * real registration would otherwise accumulate forever.
 *
 * <p><b>Why age, not {@code published_at}:</b> webhook-module's own dispatcher (ADR-0007) now
 * exists and marks rows published within seconds of being written (its default poll interval is
 * {@code clavaris.webhook.dispatch-fixed-delay-ms}, a few seconds) — but this job still sweeps by
 * age, not {@code published_at IS NOT NULL}, because a still-unpublished row past the retention
 * window is itself a real operational signal (the dispatcher has been stuck/down for the entire
 * window, an incident worth surfacing) rather than something to silently exclude from cleanup.
 * {@code retentionDays} defaults wide (90 days) specifically so the dispatcher's own real,
 * seconds-scale poll interval sits nowhere near this margin under normal operation. A
 * still-unpublished row being swept is logged as a WARN precisely so that incident is visible in
 * logs, not discovered later as a silently missing webhook delivery.
 *
 * <p>The actual sweep-and-log decision lives on {@link EventOutboxRetentionSweeper} (shared with
 * organization-module's own identical job, TD-ARCH-007) — this class only owns the bean/table/
 * schedule wiring specific to {@code event_outbox}.
 */
@Component
class EventOutboxRetentionJob {

  private static final Logger LOG = LoggerFactory.getLogger(EventOutboxRetentionJob.class);

  private final SpringDataEventOutboxJpaRepository outbox;
  private final int retentionDays;

  // Constructed only by Spring's own component scan (via @Component above).
  /* package */ EventOutboxRetentionJob(
      final SpringDataEventOutboxJpaRepository outbox,
      @Value("${clavaris.event-outbox.retention-days:90}") final int retentionDays) {
    this.outbox = outbox;
    this.retentionDays = retentionDays;
  }

  // Daily, off-peak (03:30 server time) — no other scheduled job exists yet in this codebase to
  // coordinate against, and this table has zero rows in any real environment today, so contention
  // isn't a concern; revisit the cadence once real traffic gives this table real volume.
  @Scheduled(cron = "0 30 3 * * *")
  @Transactional
  /* package */ void sweepExpiredRows() {
    EventOutboxRetentionSweeper.sweep(LOG, outbox, retentionDays);
  }
}
