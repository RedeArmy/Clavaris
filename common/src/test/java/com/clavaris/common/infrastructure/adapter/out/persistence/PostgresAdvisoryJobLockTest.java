package com.clavaris.common.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real-Postgres integration test for TD-FUT-033's own cross-instance job-lock guard — no Spring
 * context needed at all (this class has no Spring dependency beyond {@code javax.sql.DataSource}),
 * only a real {@code DataSource} pointed at a real Postgres, since {@code pg_try_advisory_lock}'s
 * session-scoped semantics are the whole thing under test and can't be faked with a mock.
 */
@Testcontainers
class PostgresAdvisoryJobLockTest {

  private static final Logger LOG = LoggerFactory.getLogger(PostgresAdvisoryJobLockTest.class);

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  private static PGSimpleDataSource dataSource;

  @BeforeAll
  static void setUpDataSource() {
    dataSource = new PGSimpleDataSource();
    dataSource.setUrl(POSTGRES.getJdbcUrl());
    dataSource.setUser(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
  }

  @Test
  void runsTheJobWhenTheLockIsNotHeldByAnyoneElse() {
    PostgresAdvisoryJobLock jobLock = new PostgresAdvisoryJobLock(dataSource);
    AtomicInteger runs = new AtomicInteger();

    jobLock.runIfLockAcquired("runs_the_job", LOG, runs::incrementAndGet);

    assertThat(runs.get()).isEqualTo(1);
  }

  // The whole reason this class exists: a second instance racing the same tick must skip the job
  // entirely, not run it redundantly — simulated here by holding the identical lock name open on
  // a separate raw connection, the same shape a genuinely separate process instance would produce.
  @Test
  void skipsTheJobWhenAnotherSessionAlreadyHoldsTheSameLock() throws SQLException {
    PostgresAdvisoryJobLock jobLock = new PostgresAdvisoryJobLock(dataSource);
    AtomicInteger runs = new AtomicInteger();

    try (Connection otherSession = dataSource.getConnection()) {
      assertThat(tryAdvisoryLock(otherSession, "skips_the_job")).isTrue();

      jobLock.runIfLockAcquired("skips_the_job", LOG, runs::incrementAndGet);

      assertThat(runs.get()).isZero();
    }
    // otherSession closing releases its session-level lock — Postgres's own documented fallback,
    // not depended on by production code (which always unlocks explicitly), but fine to rely on
    // here purely to clean up this test's own state.
  }

  // Proves the finally-based unlock in runIfLockAcquired actually runs on the success path — not
  // just that the first call works, but that it doesn't leave the lock wedged for the next tick.
  @Test
  void releasesTheLockSoASubsequentCallCanAcquireItAgain() {
    PostgresAdvisoryJobLock jobLock = new PostgresAdvisoryJobLock(dataSource);
    AtomicInteger runs = new AtomicInteger();

    jobLock.runIfLockAcquired("releases_after_success", LOG, runs::incrementAndGet);
    jobLock.runIfLockAcquired("releases_after_success", LOG, runs::incrementAndGet);

    assertThat(runs.get()).isEqualTo(2);
  }

  // Same proof as above, but for the failure path — a job that throws must not leave the lock
  // held forever (which would silently disable every future tick of that job, a far worse outcome
  // than the transient duplicate-work this lock exists to prevent).
  @Test
  void releasesTheLockEvenWhenTheJobThrows() {
    PostgresAdvisoryJobLock jobLock = new PostgresAdvisoryJobLock(dataSource);
    AtomicInteger runs = new AtomicInteger();

    assertThatThrownBy(
            () ->
                jobLock.runIfLockAcquired(
                    "releases_after_failure",
                    LOG,
                    () -> {
                      throw new IllegalStateException("simulated job failure");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("simulated job failure");

    jobLock.runIfLockAcquired("releases_after_failure", LOG, runs::incrementAndGet);

    assertThat(runs.get()).isEqualTo(1);
  }

  @Test
  void differentLockNamesNeverContendWithEachOther() throws SQLException {
    PostgresAdvisoryJobLock jobLock = new PostgresAdvisoryJobLock(dataSource);
    AtomicInteger runs = new AtomicInteger();

    try (Connection otherSession = dataSource.getConnection()) {
      assertThat(tryAdvisoryLock(otherSession, "held_by_someone_else")).isTrue();

      jobLock.runIfLockAcquired("a_completely_different_lock_name", LOG, runs::incrementAndGet);

      assertThat(runs.get()).isEqualTo(1);
    }
  }

  private static boolean tryAdvisoryLock(final Connection connection, final String lockName)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT pg_try_advisory_lock(hashtext(?))")) {
      statement.setString(1, lockName);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getBoolean(1);
      }
    }
  }

  // TD-ARCH-048: every job passes through here, so this is where each is counted, timed and
  // stamped.
  private static final Instant NOW = Instant.parse("2026-11-12T03:45:00Z");

  private static double runs(
      final SimpleMeterRegistry registry, final String job, final String outcome) {
    return registry
        .get("clavaris.job.runs")
        .tag("scheduled_job", job)
        .tag("outcome", outcome)
        .counter()
        .count();
  }

  private static double lastSuccess(final SimpleMeterRegistry registry, final String job) {
    return registry
        .get("clavaris.job.last.success.timestamp")
        .tag("scheduled_job", job)
        .gauge()
        .value();
  }

  @Test
  void aSuccessfulRunIsCountedTimedAndStamped() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    PostgresAdvisoryJobLock jobLock =
        new PostgresAdvisoryJobLock(dataSource, registry, Clock.fixed(NOW, ZoneOffset.UTC));

    jobLock.runIfLockAcquired("metrics_success", LOG, () -> {});

    assertThat(runs(registry, "metrics_success", "success")).isEqualTo(1.0);
    assertThat(runs(registry, "metrics_success", "failure")).isZero();
    assertThat(runs(registry, "metrics_success", "skipped")).isZero();
    assertThat(lastSuccess(registry, "metrics_success")).isEqualTo((double) NOW.getEpochSecond());
    assertThat(
            registry
                .get("clavaris.job.duration")
                .tag("scheduled_job", "metrics_success")
                .timer()
                .count())
        .isEqualTo(1);
  }

  @Test
  void aFailingRunIsCountedRethrownUnchangedAndNeverStamped() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    PostgresAdvisoryJobLock jobLock =
        new PostgresAdvisoryJobLock(dataSource, registry, Clock.fixed(NOW, ZoneOffset.UTC));
    IllegalStateException boom = new IllegalStateException("the sweep failed");

    assertThatThrownBy(
            () ->
                jobLock.runIfLockAcquired(
                    "metrics_failure",
                    LOG,
                    () -> {
                      throw boom;
                    }))
        .isSameAs(boom);

    assertThat(runs(registry, "metrics_failure", "failure")).isEqualTo(1.0);
    assertThat(runs(registry, "metrics_failure", "success")).isZero();
    assertThat(lastSuccess(registry, "metrics_failure"))
        .as("a job that has only ever failed reads as never succeeded, not as absent")
        .isZero();
  }

  @Test
  void aFailureThenASuccessLeavesTheStampOfTheSuccess() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    PostgresAdvisoryJobLock jobLock =
        new PostgresAdvisoryJobLock(dataSource, registry, Clock.fixed(NOW, ZoneOffset.UTC));
    try {
      jobLock.runIfLockAcquired(
          "metrics_recovers",
          LOG,
          () -> {
            throw new IllegalStateException("first run fails");
          });
    } catch (IllegalStateException _) {
      // the point is what is measured afterwards
    }

    jobLock.runIfLockAcquired("metrics_recovers", LOG, () -> {});

    assertThat(runs(registry, "metrics_recovers", "failure")).isEqualTo(1.0);
    assertThat(runs(registry, "metrics_recovers", "success")).isEqualTo(1.0);
    assertThat(lastSuccess(registry, "metrics_recovers")).isEqualTo((double) NOW.getEpochSecond());
  }

  @Test
  void aRunSkippedBecauseAnotherInstanceHoldsTheLockIsCountedApart() throws SQLException {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    PostgresAdvisoryJobLock jobLock =
        new PostgresAdvisoryJobLock(dataSource, registry, Clock.fixed(NOW, ZoneOffset.UTC));

    try (Connection otherSession = dataSource.getConnection()) {
      assertThat(tryAdvisoryLock(otherSession, "metrics_skipped")).isTrue();

      jobLock.runIfLockAcquired("metrics_skipped", LOG, () -> {});
    }

    assertThat(runs(registry, "metrics_skipped", "skipped")).isEqualTo(1.0);
    assertThat(runs(registry, "metrics_skipped", "success")).isZero();
    assertThat(runs(registry, "metrics_skipped", "failure")).isZero();
    assertThat(lastSuccess(registry, "metrics_skipped")).isZero();
  }

  @Test
  void eachJobKeepsItsOwnFigures() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    PostgresAdvisoryJobLock jobLock =
        new PostgresAdvisoryJobLock(dataSource, registry, Clock.fixed(NOW, ZoneOffset.UTC));

    jobLock.runIfLockAcquired("metrics_first", LOG, () -> {});
    jobLock.runIfLockAcquired("metrics_first", LOG, () -> {});
    jobLock.runIfLockAcquired("metrics_second", LOG, () -> {});

    assertThat(runs(registry, "metrics_first", "success")).isEqualTo(2.0);
    assertThat(runs(registry, "metrics_second", "success")).isEqualTo(1.0);
  }
}
