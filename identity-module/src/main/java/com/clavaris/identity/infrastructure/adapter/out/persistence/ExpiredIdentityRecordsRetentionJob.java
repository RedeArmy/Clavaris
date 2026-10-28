package com.clavaris.identity.infrastructure.adapter.out.persistence;

import com.clavaris.common.infrastructure.adapter.out.persistence.PostgresAdvisoryJobLock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Bounds the growth of every identity table that holds a record past its useful life. Until now
 * nothing deleted an expired verification token, a consumed email-change request, a rotated refresh
 * token or a finished session: {@code refresh_tokens} gains a row on every refresh, so it was the
 * fastest-growing table in the system and had no cleanup at all.
 *
 * <p>A row goes only once it has been expired for {@code graceDays} (default 7), never at the
 * instant it expires: the grace keeps recently expired rows available for support and for the "link
 * expired" page to tell an expired link from an unknown one, and absorbs clock skew between
 * replicas. An expired token is rejected whether or not its row exists, so deleting it changes no
 * outcome. For refresh tokens that includes reuse detection (BR-ID-03): that check fires on a
 * rotated token that is still inside its lifetime, which the grace never touches.
 *
 * <p>Refresh tokens point at their predecessor ({@code rotated_from_id}), so a row is kept while a
 * live successor still points at it; within one statement a parent and its expired child go
 * together. Sessions go only once nothing references them. Deletes run in batches so a first sweep
 * over a large backlog never holds one long lock.
 *
 * <p>{@code login_events} is the exception to "expired": a row never expires, it only ages. How
 * long sign-in history is kept is a product decision (TD-PERF-049); the default of 400 days matches
 * the known-device window (a device cookie lives 365 days) and comfortably covers the activity grid
 * on the account page. {@code clavaris.retention.login-events-days=0} keeps it forever.
 */
@Component
class ExpiredIdentityRecordsRetentionJob {

  private static final Logger LOG =
      LoggerFactory.getLogger(ExpiredIdentityRecordsRetentionJob.class);

  // Tables whose rows are tokens or pending requests with an expires_at and an id primary key.
  private static final String[] SHORT_LIVED_TABLES = {
    "verification_tokens",
    "platform_verification_tokens",
    "pending_social_links",
    "pending_platform_social_links",
    "email_change_requests"
  };

  private final JdbcTemplate jdbc;
  private final PostgresAdvisoryJobLock jobLock;
  private final int graceDays;
  private final int batchSize;
  private final int loginEventsDays;

  // Constructed only by Spring's component scan.
  /* package */ ExpiredIdentityRecordsRetentionJob(
      final JdbcTemplate jdbc,
      final PostgresAdvisoryJobLock jobLock,
      @Value("${clavaris.retention.expired-records-grace-days:7}") final int graceDays,
      @Value("${clavaris.retention.batch-size:5000}") final int batchSize,
      @Value("${clavaris.retention.login-events-days:400}") final int loginEventsDays) {
    this.jdbc = jdbc;
    this.jobLock = jobLock;
    this.graceDays = graceDays;
    this.batchSize = batchSize;
    this.loginEventsDays = loginEventsDays;
  }

  // 04:45, after the other nightly sweeps (03:30 to 04:30).
  @Scheduled(cron = "0 45 4 * * *")
  /* package */ void sweep() {
    jobLock.runIfLockAcquired("expired_identity_records", LOG, this::sweepLocked);
  }

  /** Runs one sweep now; returns the rows deleted per table. Package-visible for the test. */
  /* package */ Map<String, Integer> sweepLocked() {
    final Instant cutoff = Instant.now().minus(Duration.ofDays(graceDays));
    final Map<String, Integer> deleted = new LinkedHashMap<>();
    for (final String table : SHORT_LIVED_TABLES) {
      deleted.put(
          table,
          deleteInBatches(
              "DELETE FROM "
                  + table
                  + " WHERE id IN (SELECT id FROM "
                  + table
                  + " WHERE expires_at < ? LIMIT ?)",
              cutoff));
    }
    deleted.put(
        "refresh_tokens",
        deleteInBatches(
            "DELETE FROM refresh_tokens WHERE id IN (SELECT r.id FROM refresh_tokens r"
                + " WHERE r.expires_at < ? AND NOT EXISTS (SELECT 1 FROM refresh_tokens c"
                + " WHERE c.rotated_from_id = r.id AND c.expires_at >= ?) LIMIT ?)",
            cutoff,
            true));
    deleted.put(
        "sessions",
        deleteInBatches(
            "DELETE FROM sessions WHERE id IN (SELECT s.id FROM sessions s"
                + " WHERE COALESCE(s.revoked_at, s.last_seen_at) < ? AND NOT EXISTS"
                + " (SELECT 1 FROM refresh_tokens t WHERE t.session_id = s.id) LIMIT ?)",
            cutoff));
    if (loginEventsDays > 0) {
      deleted.put(
          "login_events",
          deleteInBatches(
              "DELETE FROM login_events WHERE id IN (SELECT id FROM login_events"
                  + " WHERE occurred_at < ? LIMIT ?)",
              Instant.now().minus(Duration.ofDays(loginEventsDays))));
    }
    deleted.forEach(
        (table, count) -> {
          if (count > 0) {
            LOG.info(
                "event=expired_records_swept table={} deleted={} graceDays={}",
                table,
                count,
                graceDays);
          }
        });
    return deleted;
  }

  private int deleteInBatches(final String sql, final Instant cutoff) {
    return deleteInBatches(sql, cutoff, false);
  }

  // The refresh-token statement takes the cutoff twice (the row, and its live successors).
  private int deleteInBatches(final String sql, final Instant cutoff, final boolean cutoffTwice) {
    // OffsetDateTime, not java.sql.Timestamp: the driver maps it to timestamptz directly.
    final OffsetDateTime bound = OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC);
    int total = 0;
    int batch;
    do {
      batch =
          cutoffTwice
              ? jdbc.update(sql, bound, bound, batchSize)
              : jdbc.update(sql, bound, batchSize);
      total += batch;
    } while (batch == batchSize);
    return total;
  }
}
