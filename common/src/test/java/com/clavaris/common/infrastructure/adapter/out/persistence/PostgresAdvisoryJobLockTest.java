package com.clavaris.common.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
}
