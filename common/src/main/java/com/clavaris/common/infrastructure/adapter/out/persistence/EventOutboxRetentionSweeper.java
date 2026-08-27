package com.clavaris.common.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;

/**
 * The age-based sweep-and-log decision every module's own event-outbox retention job runs —
 * identity-module's {@code EventOutboxRetentionJob} and organization-module's {@code
 * OrganizationEventOutboxRetentionJob} both call this instead of each carrying their own copy
 * (SonarCloud-flagged duplication on TD-ARCH-007's own PR, closed by this extraction). See {@code
 * EventOutboxRetentionJob}'s own Javadoc for the full "why age, not {@code published_at}" reasoning
 * — identical for every module's own table, since none has a real webhook dispatcher yet.
 *
 * <p>Deliberately a plain static helper, not a shared {@code @Component}/{@code @Scheduled} bean:
 * each module still owns its own job class (own bean, own table, own repository, own cron trigger)
 * — this only removes the duplicated method body, not each module's independent scheduling.
 */
public final class EventOutboxRetentionSweeper {

  @SuppressWarnings("PMD.LongVariable")
  private static final String SWEEP_LOG_MESSAGE_STILL_UNPUBLISHED =
      "event=event_outbox_retention_swept deletedCount={} stillUnpublishedCount={} retentionDays={}";

  private static final String SWEEP_LOG_MESSAGE =
      "event=event_outbox_retention_swept deletedCount={} retentionDays={}";

  private EventOutboxRetentionSweeper() {}

  public static void sweep(
      final Logger log, final EventOutboxRetentionRepository outbox, final int retentionDays) {
    final Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
    final long stillUnpublished = outbox.countByOccurredAtBeforeAndPublishedAtIsNull(cutoff);
    final long deleted = outbox.deleteByOccurredAtBefore(cutoff);
    if (deleted == 0) {
      return;
    }
    if (stillUnpublished > 0) {
      log.warn(SWEEP_LOG_MESSAGE_STILL_UNPUBLISHED, deleted, stillUnpublished, retentionDays);
    } else {
      log.info(SWEEP_LOG_MESSAGE, deleted, retentionDays);
    }
  }
}
