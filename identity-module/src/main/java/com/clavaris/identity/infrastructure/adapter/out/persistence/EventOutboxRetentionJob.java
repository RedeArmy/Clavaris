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
 * <p><b>Why age, not {@code published_at}:</b> {@code webhook-module} (ADR-0007) isn't built yet,
 * so every row's {@code published_at} stays permanently {@code NULL} — a policy that only ever
 * deleted published rows would delete nothing today and this row would stay open in name only.
 * Age-based sweeping is the honest interim policy: it accepts that any row this job deletes was
 * never going to be delivered to anyone, because nothing subscribes to this table yet. {@code
 * retentionDays} defaults wide (90 days) specifically so that once a real dispatcher exists, its
 * own poll interval only has to stay well inside that window to never lose an event to this job —
 * but the day webhook-module ships, this class must be revisited (widen the window, or gate the
 * sweep on confirmed dispatcher lag) so it doesn't start silently discarding real, undelivered
 * webhooks. A still-unpublished row being swept is logged as a WARN specifically so that day is
 * visible in logs, not discovered as a missing webhook delivery.
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
