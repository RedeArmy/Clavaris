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
 *
 * <p><b>SDE-III review, 2026-09-15 — real gap found and closed:</b> this used to only WARN with a
 * count of still-unpublished rows before deleting them right along with everything else past
 * retention — a number, and then the events themselves gone for good, no dead-letter, nothing an
 * operator paged by that WARN could actually go look at. {@link
 * EventOutboxRetentionRepository#archiveUnpublishedBefore} now copies those rows into this module's
 * own {@code *_dead_letters} table before the delete runs, in the same transaction as the delete
 * (each module's own {@code @Scheduled}/{@code @Transactional} job method wraps both calls) — so a
 * crash between the two calls rolls back cleanly instead of archiving without deleting or deleting
 * without archiving.
 */
public final class EventOutboxRetentionSweeper {

  @SuppressWarnings("PMD.LongVariable")
  private static final String SWEEP_LOG_MESSAGE_STILL_UNPUBLISHED =
      "event=event_outbox_retention_swept deletedCount={} deadLetteredCount={} retentionDays={}";

  private static final String SWEEP_LOG_MESSAGE =
      "event=event_outbox_retention_swept deletedCount={} retentionDays={}";

  private EventOutboxRetentionSweeper() {}

  public static void sweep(
      final Logger log, final EventOutboxRetentionRepository outbox, final int retentionDays) {
    final Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
    // Archive first: the dead-letter copy must exist before the row it was copied from can ever be
    // deleted, never the other way around, so a failure between the two calls never loses data
    // (worst case: an already-deleted row's dead-letter copy is missing, not the reverse).
    final long deadLettered = outbox.archiveUnpublishedBefore(cutoff);
    final long deleted = outbox.deleteByOccurredAtBefore(cutoff);
    if (deleted == 0) {
      return;
    }
    if (deadLettered > 0) {
      log.warn(SWEEP_LOG_MESSAGE_STILL_UNPUBLISHED, deleted, deadLettered, retentionDays);
    } else {
      log.info(SWEEP_LOG_MESSAGE, deleted, retentionDays);
    }
  }
}
