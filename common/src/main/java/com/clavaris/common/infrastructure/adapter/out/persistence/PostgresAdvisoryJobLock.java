package com.clavaris.common.infrastructure.adapter.out.persistence;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;

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
 * <p>The key comes from {@link AdvisoryLocks} (TD-ARCH-055), the same helper every advisory lock in
 * this codebase goes through — a stable string name hashed to 64 bits, not a hand-picked integer,
 * so a new caller never has to coordinate numeric key assignment with every existing one.
 */
// TD-ARCH-048: also the one place every scheduled job passes through, so it is where each one is
// counted, timed and stamped with its last success; see JobMeters.
public final class PostgresAdvisoryJobLock {

  // Prometheus reserves "job" for the scrape target, so the tag is renamed (exported_job).
  private static final String JOB_TAG = "scheduled_job";

  private static final String TRY_LOCK_SQL = AdvisoryLocks.TRY_SESSION_LOCK_SQL;
  private static final String UNLOCK_SQL = AdvisoryLocks.SESSION_UNLOCK_SQL;

  private final DataSource dataSource;
  private final MeterRegistry registry;
  private final Clock clock;
  private final Map<String, JobMeters> metersByJob = new ConcurrentHashMap<>();

  /**
   * The constructor Spring picks when a test imports this class as a bean (production builds it in
   * {@code ScheduledJobLockConfig} with the real registry). Without a registry the figures go to a
   * throwaway one, which is what a test that does not look at them wants.
   */
  @Autowired
  public PostgresAdvisoryJobLock(final DataSource dataSource) {
    this(dataSource, new SimpleMeterRegistry(), Clock.systemUTC());
  }

  public PostgresAdvisoryJobLock(final DataSource dataSource, final MeterRegistry registry) {
    this(dataSource, registry, Clock.systemUTC());
  }

  /* package */ PostgresAdvisoryJobLock(
      final DataSource dataSource, final MeterRegistry registry, final Clock clock) {
    this.dataSource = dataSource;
    this.registry = registry;
    this.clock = clock;
  }

  /**
   * What is exported for one job, registered the first time it is attempted (the name is only known
   * then). {@code clavaris.job.runs{scheduled_job,outcome}} counts success, failure and skipped
   * (another instance held the lock); {@code clavaris.job.duration{scheduled_job}} times a run that
   * got the lock; and {@code clavaris.job.last.success.timestamp{scheduled_job}} holds the epoch
   * second of the last success, 0 until there is one, so a job that has only ever failed reads as
   * "never succeeded" and not as absent. With several instances read it with {@code max by
   * (scheduled_job)}: one that skipped reports 0.
   */
  // The tag is scheduled_job, not job: Prometheus labels every scraped series with the target's own
  // job, and a metric tag of that name is renamed exported_job, so a query by it would group by the
  // service and not by the task.
  private static final class JobMeters {
    private final Counter success;
    private final Counter failure;
    private final Counter skipped;
    private final Timer duration;
    private final AtomicLong lastSuccessEpochSeconds = new AtomicLong();

    /* default */ JobMeters(final MeterRegistry registry, final String job) {
      this.success = runs(registry, job, "success");
      this.failure = runs(registry, job, "failure");
      this.skipped = runs(registry, job, "skipped");
      this.duration = Timer.builder("clavaris.job.duration").tag(JOB_TAG, job).register(registry);
      Gauge.builder(
              "clavaris.job.last.success.timestamp",
              lastSuccessEpochSeconds,
              AtomicLong::doubleValue)
          .baseUnit("seconds")
          .tag(JOB_TAG, job)
          .description("Epoch second of the last run of this scheduled job that succeeded")
          .register(registry);
    }

    private static Counter runs(
        final MeterRegistry registry, final String job, final String outcome) {
      return Counter.builder("clavaris.job.runs")
          .tag(JOB_TAG, job)
          .tag("outcome", outcome)
          .register(registry);
    }
  }

  private JobMeters metersOf(final String job) {
    return metersByJob.computeIfAbsent(job, name -> new JobMeters(registry, name));
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
    final JobMeters meters = metersOf(lockName);
    try (Connection connection = dataSource.getConnection()) {
      if (!tryLock(connection, lockName)) {
        log.debug("event=scheduled_job_lock_not_acquired lockName={}", lockName);
        meters.skipped.increment();
        return;
      }
      runMeasured(connection, lockName, job, meters);
    } catch (final SQLException e) {
      // A lock-infrastructure failure (e.g. the pool itself exhausted) must surface loudly, not
      // silently skip the job — same "fail loud, not silent" posture every other real failure
      // path in this codebase follows. Wrapped, not rethrown as a checked exception: every caller
      // is a @Scheduled method, and Spring's own default scheduling error handler already logs
      // and continues past an unchecked exception without cancelling this job's future runs.
      meters.failure.increment();
      throw new IllegalStateException("Failed to guard scheduled job with lock: " + lockName, e);
    }
  }

  // Counted, then rethrown unchanged: Spring's scheduling error handler still logs a failure, and
  // the caller's own handling is not altered by being measured. The lock is released either way.
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  private void runMeasured(
      final Connection connection,
      final String lockName,
      final Runnable job,
      final JobMeters meters)
      throws SQLException {
    final long started = System.nanoTime();
    try {
      job.run();
      meters.duration.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
      meters.success.increment();
      meters.lastSuccessEpochSeconds.set(clock.instant().getEpochSecond());
    } catch (final RuntimeException failure) {
      meters.duration.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
      meters.failure.increment();
      throw failure;
    } finally {
      unlock(connection, lockName);
    }
  }

  private static boolean tryLock(final Connection connection, final String lockName)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(TRY_LOCK_SQL)) {
      statement.setString(1, AdvisoryLocks.key(AdvisoryLocks.SCHEDULED_JOB, lockName));
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
      statement.setString(1, AdvisoryLocks.key(AdvisoryLocks.SCHEDULED_JOB, lockName));
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
