package com.clavaris.common.infrastructure.adapter.out.persistence;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * TD-FUT-047: keeps {@code audit_events}' monthly partitions ahead of the clock and, only when
 * asked to, drops the ones past retention. {@code V20261027090000} partitioned the table and
 * created the months up to three ahead; this makes sure the next ones exist before an event needs
 * them.
 *
 * <p><b>Partitions ahead.</b> Each nightly run creates every missing month from the current one to
 * {@code partitionsAhead} months out ({@code CREATE TABLE IF NOT EXISTS}, so it is idempotent and a
 * missed night does no harm). If the job is down longer than that, events land in the default
 * partition rather than being lost, and the job warns whenever it finds rows there: a month's
 * partition cannot be created while the default holds rows that belong to it, so that needs a human
 * (deployment-runbook section 6f).
 *
 * <p><b>Retention is off by default.</b> {@code clavaris.audit.retention-months} is 0, which keeps
 * everything: how long an audit trail must be kept is a compliance decision nobody has made yet,
 * and silently deleting evidence is the wrong default for a credential store. When it is set, a
 * partition whose whole month is older than that many months is dropped (a metadata operation, not
 * a delete), the drop is written to the audit log itself, and the runbook says how to archive a
 * partition first ({@code pg_dump} of the partition, then drop). The current and future months are
 * never dropped, whatever the setting.
 *
 * <p>Months are UTC, matching how the migration and every partition bound are written. Guarded by
 * {@link PostgresAdvisoryJobLock} like every other nightly job.
 */
@Component
class AuditEventPartitionMaintenanceJob {

  private static final Logger LOG =
      LoggerFactory.getLogger(AuditEventPartitionMaintenanceJob.class);

  private static final Pattern PARTITION_NAME = Pattern.compile("^audit_events_(\\d{4})_(\\d{2})$");

  private static final String LIST_PARTITIONS_SQL =
      "SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid"
          + " WHERE i.inhparent = 'audit_events'::regclass";

  private final JdbcTemplate jdbc;
  private final AuditEventRecorder auditEvents;
  private final PostgresAdvisoryJobLock jobLock;
  private final int retentionMonths;
  private final int partitionsAhead;
  private final Clock clock;

  @Autowired
  /* package */ AuditEventPartitionMaintenanceJob(
      final JdbcTemplate jdbc,
      final AuditEventRecorder auditEvents,
      final PostgresAdvisoryJobLock jobLock,
      @Value("${clavaris.audit.retention-months:0}") final int retentionMonths,
      @Value("${clavaris.audit.partitions-ahead:3}") final int partitionsAhead) {
    this(jdbc, auditEvents, jobLock, retentionMonths, partitionsAhead, Clock.systemUTC());
  }

  /* package */ AuditEventPartitionMaintenanceJob(
      final JdbcTemplate jdbc,
      final AuditEventRecorder auditEvents,
      final PostgresAdvisoryJobLock jobLock,
      final int retentionMonths,
      final int partitionsAhead,
      final Clock clock) {
    this.jdbc = jdbc;
    this.auditEvents = auditEvents;
    this.jobLock = jobLock;
    this.retentionMonths = retentionMonths;
    this.partitionsAhead = partitionsAhead;
    this.clock = clock;
  }

  // Once at start-up as well as nightly: after a deploy that follows a long stop, waiting for the
  // night would leave events in the default partition in the meantime. A failure here must not stop
  // the application starting; the nightly run retries.
  @EventListener(ApplicationReadyEvent.class)
  /* package */ void maintainAtStartUp() {
    try {
      maintain();
    } catch (final DataAccessException failure) {
      LOG.error(
          "event=audit_events_partition_maintenance_failed exceptionType={}",
          failure.getClass().getName());
    }
  }

  // Daily, off-peak (03:50), after the other nightly sweeps (03:30 to 03:45).
  @Scheduled(cron = "0 50 3 * * *")
  /* package */ void maintain() {
    jobLock.runIfLockAcquired("audit_events_partitions", LOG, this::maintainLocked);
  }

  /* package */ void maintainLocked() {
    final YearMonth current = YearMonth.now(clock.withZone(ZoneOffset.UTC));
    ensurePartitions(current);
    warnIfTheDefaultPartitionHoldsRows();
    if (retentionMonths > 0) {
      dropExpiredPartitions(current.minusMonths(retentionMonths));
    }
  }

  // java:S2077: the statement is built from a YearMonth we computed, never from input, and DDL
  // cannot take bind parameters.
  @SuppressWarnings("java:S2077")
  private void ensurePartitions(final YearMonth current) {
    for (int ahead = 0; ahead <= partitionsAhead; ahead++) {
      final YearMonth month = current.plusMonths(ahead);
      // The name and both bounds come from a YearMonth, never from input, so formatting them into
      // the statement is safe; DDL cannot take bind parameters.
      jdbc.execute(
          "CREATE TABLE IF NOT EXISTS "
              + partitionName(month)
              + " PARTITION OF audit_events FOR VALUES FROM ('"
              + month.atDay(1)
              + " 00:00:00+00') TO ('"
              + month.plusMonths(1).atDay(1)
              + " 00:00:00+00')");
    }
  }

  private void warnIfTheDefaultPartitionHoldsRows() {
    final Boolean holdsRows =
        jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM audit_events_default)", Boolean.class);
    if (Boolean.TRUE.equals(holdsRows)) {
      LOG.warn(
          "event=audit_events_default_partition_in_use hint=create_the_missing_month_partitions");
    }
  }

  // java:S2077: the name is rebuilt from a YearMonth parsed out of a catalog name that matched the
  // strict audit_events_YYYY_MM pattern, so no catalog text reaches the statement as written.
  @SuppressWarnings("java:S2077")
  private void dropExpiredPartitions(final YearMonth oldestKept) {
    final List<YearMonth> expired = new ArrayList<>();
    for (final String name : jdbc.queryForList(LIST_PARTITIONS_SQL, String.class)) {
      final Matcher matcher = PARTITION_NAME.matcher(name);
      if (matcher.matches()) {
        final YearMonth month =
            YearMonth.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
        if (month.isBefore(oldestKept)) {
          expired.add(month);
        }
      }
    }
    expired.sort(null);
    for (final YearMonth month : expired) {
      jdbc.execute("DROP TABLE " + partitionName(month));
      LOG.warn(
          "event=audit_events_partition_dropped month={} retentionMonths={}",
          month,
          retentionMonths);
      auditEvents.write(
          AuditActor.platformClient("system:audit-retention-job"),
          "audit_log.partition_dropped",
          "AuditLog",
          partitionName(month),
          "month=" + month + " retentionMonths=" + retentionMonths);
    }
  }

  private static String partitionName(final YearMonth month) {
    return String.format("audit_events_%04d_%02d", month.getYear(), month.getMonthValue());
  }
}
