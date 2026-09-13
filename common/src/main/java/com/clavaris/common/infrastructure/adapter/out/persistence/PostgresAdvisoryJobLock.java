package com.clavaris.common.infrastructure.adapter.out.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.slf4j.Logger;

/**
 * TD-FUT-033: a session-level Postgres advisory-lock guard for a {@code @Scheduled} job's own body
 * — shared by every one of this codebase's 8 real nightly retention/cleanup jobs that had no
 * cross-instance duplicate-run guard at all (unlike {@code WebhookDispatchScheduler}'s own two
 * ticks, already safe via {@code SELECT ... FOR UPDATE SKIP LOCKED}). Harmless today (exactly one
 * instance of this process runs, {@code TD-FUT-020} hasn't shipped), real wasted work the day a
 * second instance comes online.
 *
 * <p><b>Why a dedicated raw {@link Connection}, not {@code JdbcTemplate}:</b> Postgres advisory
 * locks acquired via {@code pg_try_advisory_lock} are <b>session</b>-scoped, not transaction-
 * scoped — they persist until explicitly released via {@code pg_advisory_unlock} on that exact same
 * physical connection/session, regardless of any transaction commit/rollback on it. {@code
 * JdbcTemplate} (and Spring's own transaction-synchronized {@code DataSourceUtils}) borrows a
 * connection from the pool per call with no guarantee the same physical connection backs two
 * separate calls — acquiring the lock on one pooled connection and attempting to release it via a
 * <i>different</i> one would either silently fail to release anything (Postgres has no cross-
 * session unlock) or, worse, leave the lock held on a connection HikariCP later hands back out for
 * unrelated work, wedging every future attempt to acquire that same lock. This class sidesteps that
 * entirely by acquiring one {@link Connection} directly from the {@link DataSource} and holding it
 * — try-lock, run the guarded job, unlock, always in that order on that one connection — for the
 * whole guarded call, then returning it to the pool. Deliberately {@code pg_try_advisory_lock}
 * (non-blocking), not {@code pg_advisory_lock} (blocking): a second instance racing the same tick
 * must skip this run entirely, not queue behind the first instance and then run redundantly anyway
 * once it finishes.
 *
 * <p>{@code hashtext(?)} keying matches this codebase's own established advisory-lock convention
 * ({@code WorkspaceMembershipRepository#lockForRoleChange}, {@code
 * SigningKeyRepository#lockForRotation}) — a stable string name, not a hand-picked integer, so a
 * new caller never has to coordinate numeric key assignment with every existing one.
 */
public final class PostgresAdvisoryJobLock {

  private static final String TRY_LOCK_SQL = "SELECT pg_try_advisory_lock(hashtext(?))";
  private static final String UNLOCK_SQL = "SELECT pg_advisory_unlock(hashtext(?))";

  private final DataSource dataSource;

  public PostgresAdvisoryJobLock(final DataSource dataSource) {
    this.dataSource = dataSource;
  }

  /**
   * Runs {@code job} only if this instance acquires the named lock; otherwise logs at DEBUG and
   * returns without running {@code job} at all — another instance already holds it for this exact
   * {@code lockName}, so this tick's own work is redundant, not missing (the lock holder's own run
   * already covers it).
   *
   * <p>{@code job} is free to open its own separate, ordinary {@code @Transactional} boundary (via
   * Spring AOP on the calling bean, e.g. self-invoking a private method from within an outer
   * {@code @Transactional} {@code @Scheduled} method) — that transaction runs on Spring's own
   * transaction-synchronized connection, entirely separate from the raw connection this method
   * holds open only for the advisory lock itself. The two never conflict.
   */
  public void runIfLockAcquired(final String lockName, final Logger log, final Runnable job) {
    try (Connection connection = dataSource.getConnection()) {
      if (!tryLock(connection, lockName)) {
        log.debug("event=scheduled_job_lock_not_acquired lockName={}", lockName);
        return;
      }
      try {
        job.run();
      } finally {
        unlock(connection, lockName);
      }
    } catch (final SQLException e) {
      // A lock-infrastructure failure (e.g. the pool itself exhausted) must surface loudly, not
      // silently skip the job — same "fail loud, not silent" posture every other real failure
      // path in this codebase follows. Wrapped, not rethrown as a checked exception: every caller
      // is a @Scheduled method, and Spring's own default scheduling error handler already logs
      // and continues past an unchecked exception without cancelling this job's future runs.
      throw new IllegalStateException("Failed to guard scheduled job with lock: " + lockName, e);
    }
  }

  private static boolean tryLock(final Connection connection, final String lockName)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(TRY_LOCK_SQL)) {
      statement.setString(1, lockName);
      try (ResultSet resultSet = statement.executeQuery()) {
        requireRow(resultSet, lockName);
        return resultSet.getBoolean(1);
      }
    }
  }

  // Best-effort: this connection is only ever returned to the pool once this method's own
  // try-with-resources closes it, at which point Postgres would also release every advisory lock
  // still held on that session as a fallback — but the explicit unlock here, always reached via
  // the caller's own finally block, is the primary release path, not a backstop.
  private static void unlock(final Connection connection, final String lockName)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(UNLOCK_SQL)) {
      statement.setString(1, lockName);
      try (ResultSet resultSet = statement.executeQuery()) {
        requireRow(resultSet, lockName); // the boolean itself is discarded — see below.
      }
    }
  }

  // PMD.CheckResultSet: both callers already read exactly the row this checks for — this is that
  // check, factored out once rather than duplicated. `pg_try_advisory_lock`/`pg_advisory_unlock`
  // are scalar functions called via a plain SELECT, so exactly one row is always the real Postgres
  // contract, not an assumption; a missing row here would mean the query itself is broken, not a
  // normal outcome worth silently tolerating.
  private static void requireRow(final ResultSet resultSet, final String lockName)
      throws SQLException {
    if (!resultSet.next()) {
      throw new IllegalStateException("Advisory lock query returned no row for lock: " + lockName);
    }
  }
}
