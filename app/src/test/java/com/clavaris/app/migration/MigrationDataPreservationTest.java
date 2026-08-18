package com.clavaris.app.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Worked example, not a test of real schema (data-model.md §4 — no bounded context owns real
 * migrations yet). Proves the methodology every future migration that ALTERS an existing table must
 * follow: apply schema up to N-1, seed representative data, apply migration N, assert the data is
 * still there and correctly transformed. A migration that only ever runs against an empty database
 * (every test elsewhere in this project so far) never actually proves this — this class exists
 * specifically to close that gap.
 *
 * <p>The migrations under test live in src/test/resources/db/migration-pattern- example,
 * deliberately separate from the real app/src/main/resources/db/ migration history — this is a
 * template to copy per future migration that touches existing data, not itself part of what ships.
 */
@Testcontainers
class MigrationDataPreservationTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  private static final String MIGRATIONS_LOCATION = "classpath:db/migration-pattern-example";

  @Test
  void columnRenameMigrationPreservesExistingRows() throws Exception {
    // Bring the schema to exactly the state before the migration under test.
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations(MIGRATIONS_LOCATION)
        .target("1")
        .load()
        .migrate();

    // Seed data on the pre-migration schema — this is the whole point:
    // a migration that only ever runs against an empty table proves nothing.
    UUID seededId = UUID.randomUUID();
    try (Connection connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement statement = connection.createStatement()) {
      statement.executeUpdate(
          "INSERT INTO migration_pattern_demo (id, legacy_status) VALUES ('"
              + seededId
              + "', 'ACTIVE')");
    }

    // Apply the migration being validated (V2 — renames legacy_status to status).
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations(MIGRATIONS_LOCATION)
        .load()
        .migrate();

    // The row seeded before the migration must still exist, unchanged in
    // substance, under the new column name — not lost, not defaulted, not
    // silently reset.
    try (Connection connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "SELECT status FROM migration_pattern_demo WHERE id = '" + seededId + "'")) {

      assertThat(rows.next())
          .as("row seeded before the migration must still exist after it")
          .isTrue();
      assertThat(rows.getString("status"))
          .as("value must survive the rename intact, not be lost or defaulted")
          .isEqualTo("ACTIVE");
      assertThat(rows.next())
          .as("exactly one row — the migration must not duplicate or fan out data")
          .isFalse();
    }
  }
}
