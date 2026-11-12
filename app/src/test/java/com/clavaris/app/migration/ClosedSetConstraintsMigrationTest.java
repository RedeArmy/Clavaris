package com.clavaris.app.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.clavaris.clientregistry.domain.model.ClientDomainMode;
import com.clavaris.clientregistry.domain.model.DomainVerificationStatus;
import com.clavaris.common.domain.image.ImageSignature;
import com.clavaris.identity.domain.model.AccountStatus;
import com.clavaris.identity.domain.model.VerificationTokenType;
import com.clavaris.organization.domain.model.RestrictionType;
import com.clavaris.webhook.domain.model.WebhookDeliveryStatus;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * TD-ARCH-054 and TD-PERF-065, on a database that already holds rows.
 *
 * <p>The schema is brought to the last migration before the new ones, every table that is about to
 * get a CHECK is seeded with a row for each value the Java enum can write (and with NULL where the
 * column allows it), the remaining migrations are applied, and the test asserts that nothing was
 * lost, that every constraint ended validated, and that a value outside the set is now refused. A
 * second run seeds one value the code never writes and shows the validating migration stops, as its
 * comment says it does.
 *
 * <p>It also holds the constraints to the code: the allowed set of each CHECK must equal the enum's
 * names, so an enum that gains a value without a migration fails here and not in production.
 */
@Testcontainers
class ClosedSetConstraintsMigrationTest {

  /** The last migration that was on master before the closed-set constraints. */
  private static final String BEFORE = "20261112100000";

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @BeforeAll
  static void createSharedExtensions() throws SQLException {
    // Created once, in public, before any schema is migrated: an extension lives in one schema, and
    // the second schema's migrations would not find pg_trgm's operator classes in the first.
    try (Connection connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm SCHEMA public");
      statement.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto SCHEMA public");
    }
  }

  private record Guarded(String table, String column, List<String> values, boolean nullable) {}

  private static List<String> names(final Enum<?>[] constants) {
    return Arrays.stream(constants).map(Enum::name).toList();
  }

  private static List<Guarded> guarded() {
    final List<String> providers = List.of("GOOGLE", "GITHUB");
    return List.of(
        new Guarded("accounts", "status", names(AccountStatus.values()), false),
        new Guarded("platform_accounts", "status", names(AccountStatus.values()), false),
        new Guarded("social_identities", "provider", providers, false),
        new Guarded("platform_social_identities", "provider", providers, false),
        new Guarded("pending_social_links", "provider", providers, false),
        new Guarded("pending_platform_social_links", "provider", providers, false),
        new Guarded("verification_tokens", "type", names(VerificationTokenType.values()), false),
        new Guarded(
            "platform_verification_tokens", "type", names(VerificationTokenType.values()), false),
        new Guarded("access_restriction_entries", "type", names(RestrictionType.values()), false),
        new Guarded(
            "organization_logos",
            "content_type",
            List.of(
                ImageSignature.PNG, ImageSignature.JPEG, ImageSignature.GIF, ImageSignature.WEBP),
            false),
        new Guarded("organization_social_connection_settings", "provider", providers, false),
        new Guarded("organization_social_credentials", "provider", providers, false),
        new Guarded("client_domain_configs", "mode", names(ClientDomainMode.values()), true),
        new Guarded(
            "client_domain_configs",
            "verification_status",
            names(DomainVerificationStatus.values()),
            true),
        new Guarded("webhook_deliveries", "status", names(WebhookDeliveryStatus.values()), false));
  }

  private static final List<String> VACUUMED =
      List.of(
          "refresh_tokens",
          "verification_tokens",
          "sessions",
          "event_outbox",
          "organization_event_outbox",
          "oauth2_authorization",
          "webhook_deliveries");

  private static Flyway flyway(final String schema, final String target) {
    return Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("classpath:db/migration")
        // TD-FUT-039: the default advisory lock keeps a transaction open, which a CREATE INDEX
        // CONCURRENTLY migration waits on forever; same setting as app's application.yml.
        .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
        // "public" stays on the search path: pg_trgm and pgcrypto are created once per database, in
        // the first schema that asks, and a second schema could not see their operator classes.
        .schemas(schema, "public")
        .defaultSchema(schema)
        .target(target)
        .load();
  }

  private static Connection connection(final String schema) throws SQLException {
    final Connection connection =
        DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    try (Statement statement = connection.createStatement()) {
      statement.execute("SET search_path TO " + schema + ", public");
      // The seeded rows point at parents that are not there; the foreign keys are not what is under
      // test, and this superuser session may switch them off for itself.
      statement.execute("SET session_replication_role = replica");
    }
    return connection;
  }

  /** Inserts one row, giving every other required column a throwaway value of its own type. */
  private static void seed(
      final Connection connection,
      final String schema,
      final String table,
      final String column,
      final String value)
      throws SQLException {
    final List<String> columns = new ArrayList<>();
    final List<String> placeholders = new ArrayList<>();
    final List<Object> parameters = new ArrayList<>();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT column_name, data_type, is_nullable, column_default "
                + "FROM information_schema.columns WHERE table_schema = ? AND table_name = ? "
                + "ORDER BY ordinal_position")) {
      statement.setString(1, schema);
      statement.setString(2, table);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          final String name = rows.getString("column_name");
          final boolean target = name.equals(column);
          final boolean required =
              "NO".equals(rows.getString("is_nullable"))
                  && rows.getString("column_default") == null;
          if (!target && !required) {
            continue;
          }
          columns.add(name);
          if (target) {
            placeholders.add(value == null ? "NULL" : "?");
            parameters.add(value);
          } else {
            placeholders.add(dummyPlaceholder(rows.getString("data_type")));
            parameters.add(dummyValue(rows.getString("data_type")));
          }
        }
      }
    }
    final String sql =
        "INSERT INTO "
            + table
            + " ("
            + String.join(", ", columns)
            + ") VALUES ("
            + String.join(", ", placeholders)
            + ")";
    try (PreparedStatement insert = connection.prepareStatement(sql)) {
      int index = 1;
      for (final Object parameter : parameters) {
        if (parameter != null) {
          insert.setObject(index++, parameter);
        }
      }
      insert.executeUpdate();
    }
  }

  private static String dummyPlaceholder(final String dataType) {
    return switch (dataType) {
      case "jsonb" -> "'[]'::jsonb";
      case "json" -> "'[]'::json";
      case "timestamp with time zone" -> "now()";
      default -> "?";
    };
  }

  private static Object dummyValue(final String dataType) {
    return switch (dataType) {
      case "uuid" -> UUID.randomUUID();
      case "boolean" -> false;
      case "integer", "bigint", "smallint" -> 1;
      case "numeric" -> 1;
      case "bytea" -> new byte[] {1};
      case "jsonb", "json", "timestamp with time zone" -> null;
      default -> "seed-" + UUID.randomUUID().toString().substring(0, 8);
    };
  }

  private static long count(final Connection connection, final String table) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("SELECT count(*) FROM " + table)) {
      rows.next();
      return rows.getLong(1);
    }
  }

  private static String constraintDefinition(
      final Connection connection, final String table, final String column) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT pg_get_constraintdef(oid), convalidated FROM pg_constraint "
                + "WHERE conrelid = ?::regclass AND conname = ?")) {
      statement.setString(1, table);
      statement.setString(2, "ck_" + table + "_" + column);
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).as("ck_%s_%s exists", table, column).isTrue();
        assertThat(rows.getBoolean(2)).as("ck_%s_%s is validated", table, column).isTrue();
        return rows.getString(1);
      }
    }
  }

  @Test
  void rowsAlreadyInTheTablesSurviveAndEveryConstraintEndsValidated() throws Exception {
    final String schema = "preserved";
    flyway(schema, BEFORE).migrate();

    final Map<String, Long> before = new LinkedHashMap<>();
    try (Connection connection = connection(schema)) {
      for (final Guarded guard : guarded()) {
        for (final String value : guard.values()) {
          seed(connection, schema, guard.table(), guard.column(), value);
        }
        if (guard.nullable()) {
          seed(connection, schema, guard.table(), guard.column(), null);
        }
      }
      for (final Guarded guard : guarded()) {
        before.put(guard.table(), count(connection, guard.table()));
      }
    }

    flyway(schema, "latest").migrate();

    try (Connection connection = connection(schema)) {
      for (final Map.Entry<String, Long> entry : before.entrySet()) {
        assertThat(count(connection, entry.getKey()))
            .as("rows of %s after the migrations", entry.getKey())
            .isEqualTo(entry.getValue());
      }
      for (final Guarded guard : guarded()) {
        final String definition = constraintDefinition(connection, guard.table(), guard.column());
        assertThat(allowedValues(definition))
            .as(
                "the CHECK on %s.%s must allow exactly what the code writes",
                guard.table(), guard.column())
            .containsExactlyElementsOf(new TreeSet<>(guard.values()));
      }
      for (final Guarded guard : guarded()) {
        assertThatThrownBy(() -> seed(connection, schema, guard.table(), guard.column(), "ZZZ"))
            .as("%s.%s refuses a value outside the set", guard.table(), guard.column())
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("ck_" + guard.table() + "_" + guard.column());
      }
      assertThat(autovacuumTuned(connection))
          .as("the churn tables carry their own autovacuum settings")
          .containsAll(VACUUMED);
      try (PreparedStatement statement =
          connection.prepareStatement(
              "SELECT count(*) FROM pg_indexes WHERE schemaname = ? "
                  + "AND indexname = 'ix_organizations_linked_environment'"); ) {
        statement.setString(1, schema);
        try (ResultSet rows = statement.executeQuery()) {
          rows.next();
          assertThat(rows.getInt(1)).as("the foreign key's index exists").isEqualTo(1);
        }
      }
    }
  }

  @Test
  void aValueTheCodeNeverWritesStopsTheValidationInsteadOfSlippingThrough() throws Exception {
    final String schema = "badrow";
    flyway(schema, BEFORE).migrate();
    try (Connection connection = connection(schema)) {
      seed(connection, schema, "webhook_deliveries", "status", "LEGACY_STATE");
    }

    assertThatThrownBy(() -> flyway(schema, "latest").migrate())
        .isInstanceOf(FlywayException.class)
        .hasMessageContaining("ck_webhook_deliveries_status");
  }

  private static List<String> autovacuumTuned(final Connection connection) throws SQLException {
    final List<String> tables = new ArrayList<>();
    try (Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "SELECT relname FROM pg_class WHERE reloptions::text LIKE "
                    + "'%autovacuum_vacuum_scale_factor=0.02%' "
                    + "AND relnamespace = current_schema()::regnamespace")) {
      while (rows.next()) {
        tables.add(rows.getString(1));
      }
    }
    return tables;
  }

  private static final Pattern QUOTED = Pattern.compile("'([^']+)'::");

  /** The values of a definition such as {@code CHECK ((status)::text = ANY (ARRAY['A'::...]))}. */
  private static TreeSet<String> allowedValues(final String definition) {
    final TreeSet<String> values = new TreeSet<>();
    final Matcher matcher = QUOTED.matcher(definition);
    while (matcher.find()) {
      values.add(matcher.group(1));
    }
    return values;
  }
}
