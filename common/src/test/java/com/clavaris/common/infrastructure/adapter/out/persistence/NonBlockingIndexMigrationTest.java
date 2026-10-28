package com.clavaris.common.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.awaitility.core.ConditionTimeoutException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * TD-FUT-039, against a real Postgres. An index added to a table that already holds rows blocks
 * every writer for the whole build; {@code CREATE INDEX CONCURRENTLY} does not, but cannot run in a
 * transaction. This proves what the repository's convention rests on: Flyway detects the statement
 * and runs that migration non-transactionally with no marker file (given the session-level advisory
 * lock the application configures), and a concurrent build leaves writers alone where a plain one
 * makes them queue behind it.
 */
@Testcontainers
class NonBlockingIndexMigrationTest {

  private static final String LOCATION = "classpath:db/concurrent-index-test";

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  private static PGSimpleDataSource dataSource;
  private static JdbcTemplate jdbc;

  @BeforeAll
  static void connect() {
    dataSource = new PGSimpleDataSource();
    dataSource.setUrl(POSTGRES.getJdbcUrl());
    dataSource.setUser(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    jdbc = new JdbcTemplate(dataSource);
  }

  @BeforeEach
  void emptySchema() {
    jdbc.execute("DROP SCHEMA public CASCADE");
    jdbc.execute("CREATE SCHEMA public");
  }

  private static Flyway flyway() {
    // The session-level advisory lock is what the application runs with too: the default
    // transactional one keeps a transaction open for the whole migration, and CREATE INDEX
    // CONCURRENTLY waits for every older transaction, so it would wait on Flyway itself.
    return Flyway.configure()
        .dataSource(dataSource)
        .locations(LOCATION)
        .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
        .load();
  }

  private boolean indexIsValid(final String name) {
    final Boolean valid =
        jdbc.query(
            "SELECT i.indisvalid FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid"
                + " WHERE c.relname = ?",
            rs -> rs.next() ? rs.getBoolean(1) : null,
            name);
    return Boolean.TRUE.equals(valid);
  }

  @Test
  void aMigrationWithAConcurrentIndexNeedsNoMarkerAndBuildsIt() {
    flyway().migrate();

    assertThat(indexIsValid("ix_hot_events_organization_occurred")).isTrue();
  }

  /**
   * An open writer holds a row lock on the table, which is what a busy table always has. The build
   * then has to wait for it, and what matters is what a NEW writer does meanwhile.
   */
  private boolean aNewWriterGetsThroughWhileABuildWaitsBehindAnOpenWriter(final String buildSql)
      throws Exception {
    jdbc.execute(
        "CREATE TABLE hot_events (id bigserial PRIMARY KEY, organization_id uuid NOT NULL,"
            + " occurred_at timestamptz NOT NULL DEFAULT now())");
    try (Connection openWriter = dataSource.getConnection()) {
      openWriter.setAutoCommit(false);
      try (Statement statement = openWriter.createStatement()) {
        statement.execute(
            "INSERT INTO hot_events (organization_id) VALUES ('" + UUID.randomUUID() + "')");
      }
      final CompletableFuture<Void> build =
          CompletableFuture.runAsync(
              () -> {
                try (Connection connection = dataSource.getConnection();
                    Statement statement = connection.createStatement()) {
                  statement.execute(buildSql);
                } catch (final SQLException e) {
                  throw new IllegalStateException(e);
                }
              });
      awaitBuildWaitingForTheLock();

      boolean writerGotThrough = true;
      try (Connection newWriter = dataSource.getConnection();
          Statement statement = newWriter.createStatement()) {
        statement.execute("SET lock_timeout = '2s'");
        statement.execute(
            "INSERT INTO hot_events (organization_id) VALUES ('" + UUID.randomUUID() + "')");
      } catch (final SQLException _) {
        writerGotThrough = false; // lock_timeout: it queued behind the build
      }
      openWriter.commit();
      build.get(30, TimeUnit.SECONDS);
      return writerGotThrough;
    }
  }

  private void awaitBuildWaitingForTheLock() {
    try {
      await()
          .atMost(Duration.ofSeconds(10))
          .pollInterval(Duration.ofMillis(100))
          .until(
              () -> {
                final Integer waiting =
                    jdbc.queryForObject(
                        "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'"
                            + " AND query ILIKE 'CREATE INDEX%'",
                        Integer.class);
                return waiting != null && waiting > 0;
              });
    } catch (final ConditionTimeoutException _) {
      throw new AssertionError("the index build never started waiting for the open writer");
    }
  }

  @Test
  void aConcurrentBuildLeavesNewWritersAloneWhileItWaits() throws Exception {
    assertThat(
            aNewWriterGetsThroughWhileABuildWaitsBehindAnOpenWriter(
                "CREATE INDEX CONCURRENTLY ix_hot_events ON hot_events (organization_id)"))
        .isTrue();
    assertThat(indexIsValid("ix_hot_events")).isTrue();
  }

  // The control: it is what makes the test above mean something, because it fails the same check
  // when the build is the plain one a transactional migration would run.
  @Test
  void aPlainBuildMakesNewWritersQueueBehindIt() throws Exception {
    assertThat(
            aNewWriterGetsThroughWhileABuildWaitsBehindAnOpenWriter(
                "CREATE INDEX ix_hot_events ON hot_events (organization_id)"))
        .isFalse();
  }
}
