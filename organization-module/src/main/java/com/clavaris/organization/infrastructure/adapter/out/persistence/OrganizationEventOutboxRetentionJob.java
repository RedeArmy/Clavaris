package com.clavaris.organization.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * TD-ARCH-007: bounds this module's own {@code organization_event_outbox} growth —
 * identity-module's own {@code EventOutboxRetentionJob} mirror, same age-based sweep, same {@code
 * clavaris.event-outbox.retention-days} property (one shared knob governs both tables' retention,
 * deliberately — there is no reason the two policies would ever need to diverge). Module-prefixed
 * class name, same collision reason as {@link OrganizationEventOutboxEntity}'s own Javadoc. See
 * identity-module's own class for the full "why age, not published_at" reasoning; identical here.
 */
@Component
class OrganizationEventOutboxRetentionJob {

  private static final Logger LOG =
      LoggerFactory.getLogger(OrganizationEventOutboxRetentionJob.class);

  private final SpringDataOrganizationEventOutboxJpaRepository outbox;
  private final int retentionDays;

  /* package */ OrganizationEventOutboxRetentionJob(
      final SpringDataOrganizationEventOutboxJpaRepository outbox,
      @Value("${clavaris.event-outbox.retention-days:90}") final int retentionDays) {
    this.outbox = outbox;
    this.retentionDays = retentionDays;
  }

  // Same daily, off-peak cadence as identity-module's own identical job — the two run
  // independently against their own, separate tables, no coordination needed between them.
  @Scheduled(cron = "0 30 3 * * *")
  @Transactional
  /* package */ void sweepExpiredRows() {
    final Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
    final long stillUnpublished = outbox.countByOccurredAtBeforeAndPublishedAtIsNull(cutoff);
    final long deleted = outbox.deleteByOccurredAtBefore(cutoff);
    if (deleted == 0) {
      return;
    }
    if (stillUnpublished > 0) {
      LOG.warn(
          "event=event_outbox_retention_swept deletedCount={} stillUnpublishedCount={}"
              + " retentionDays={}",
          deleted,
          stillUnpublished,
          retentionDays);
    } else {
      LOG.info(
          "event=event_outbox_retention_swept deletedCount={} retentionDays={}",
          deleted,
          retentionDays);
    }
  }
}
