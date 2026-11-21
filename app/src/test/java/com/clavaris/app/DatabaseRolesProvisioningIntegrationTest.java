package com.clavaris.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

/**
 * TD-SEC-106, against a real Postgres and the real {@code infra/postgres/provision-roles.sql}: the
 * application's database role can read and write rows and nothing else, the migration role owns the
 * schema, and the audit log is append-only for the application.
 *
 * <p>The state it starts from is the one a host that already runs Clavaris is in: every migration
 * applied as the superuser the image creates, so every table belongs to that superuser. The script
 * then moves them to {@code clavaris_owner} and Flyway runs again as that role, which is the first
 * deploy after this change.
 */
@Testcontainers
class DatabaseRolesProvisioningIntegrationTest {

  private static final String OWNER_PASSWORD = "owner-password-for-the-test-1";
  private static final String APP_PASSWORD = "app-password-for-the-test-1";

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16")
          .withCopyFileToContainer(
              MountableFile.forHostPath(
                  Path.of("..", "infra", "postgres", "provision-roles.sql").toAbsolutePath()),
              "/tmp/provision-roles.sql");

  @BeforeAll
  static void legacyDatabaseThenProvisioned() throws Exception {
    // As the superuser, like every deploy until now.
    flyway(POSTGRES.getUsername(), POSTGRES.getPassword()).migrate();
    provision(OWNER_PASSWORD, APP_PASSWORD);
    // The first deploy afterwards: Flyway as the owner finds nothing to do and must be able to.
    flyway("clavaris_owner", OWNER_PASSWORD).migrate();
  }

  private static Flyway flyway(final String user, final String password) {
    return Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), user, password)
        .locations("classpath:db/migration")
        // application.yml's own setting (TD-FUT-039): a hand-built Flyway does not read it.
        .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
        .load();
  }

  private static void provision(final String ownerPassword, final String appPassword)
      throws Exception {
    final ExecResult result =
        POSTGRES.execInContainer(
            "psql",
            "-X",
            "-q",
            "-v",
            "ON_ERROR_STOP=1",
            "-v",
            "owner_password=" + ownerPassword,
            "-v",
            "app_password=" + appPassword,
            "-U",
            POSTGRES.getUsername(),
            "-d",
            POSTGRES.getDatabaseName(),
            "-f",
            "/tmp/provision-roles.sql");
    assertThat(result.getExitCode()).as(result.getStdout() + result.getStderr()).isZero();
  }

  private static Connection connectAs(final String user, final String password)
      throws SQLException {
    return DriverManager.getConnection(POSTGRES.getJdbcUrl(), user, password);
  }

  private static Connection app() throws SQLException {
    return connectAs("clavaris_app", APP_PASSWORD);
  }

  private static Connection owner() throws SQLException {
    return connectAs("clavaris_owner", OWNER_PASSWORD);
  }

  private static Connection superuser() throws SQLException {
    return connectAs(POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private static List<String> column(final Connection connection, final String sql)
      throws SQLException {
    final List<String> values = new ArrayList<>();
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      while (rows.next()) {
        values.add(rows.getString(1));
      }
    }
    return values;
  }

  private static String sqlState(final ThrowingSql action) {
    try {
      action.run();
    } catch (SQLException e) {
      return e.getSQLState();
    }
    return "no error";
  }

  private interface ThrowingSql {
    void run() throws SQLException;
  }

  private static void execute(final Connection connection, final String sql) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private static void insertAuditEvent(final Connection connection, final String occurredAt)
      throws SQLException {
    execute(
        connection,
        "INSERT INTO audit_events (id, actor_type, actor_id, action, target_type, target_id,"
            + " occurred_at) VALUES ('"
            + UUID.randomUUID()
            + "', 'PLATFORM_CLIENT', 'c', 'test.action', 'Organization', 't', '"
            + occurredAt
            + "'::timestamptz)");
  }

  // ---- who the roles are ----

  @Test
  void neitherRoleIsPrivilegedAndTheApplicationOnlyConnects() throws Exception {
    try (Connection connection = superuser()) {
      assertThat(
              column(
                  connection,
                  "SELECT rolname || ':' || rolsuper || rolcreaterole || rolcreatedb ||"
                      + " rolreplication || rolbypassrls FROM pg_roles"
                      + " WHERE rolname IN ('clavaris_app', 'clavaris_owner') ORDER BY rolname"))
          .containsExactly(
              "clavaris_app:falsefalsefalsefalsefalse", "clavaris_owner:falsefalsefalsefalsefalse");
    }
    try (Connection connection = app()) {
      assertThat(column(connection, "SELECT current_user")).containsExactly("clavaris_app");
    }
  }

  @Test
  void theSchemaBelongsToTheMigrationRoleNotToTheSuperuserTheImageCreated() throws Exception {
    try (Connection connection = superuser()) {
      assertThat(
              column(
                  connection,
                  "SELECT tablename FROM pg_tables WHERE schemaname = 'public'"
                      + " AND tableowner <> 'clavaris_owner'"))
          .as("tables, partitions and the migration history all moved to the owner")
          .isEmpty();
      assertThat(
              column(
                  connection,
                  "SELECT proname FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace"
                      + " WHERE n.nspname = 'public' AND pg_get_userbyid(p.proowner) <>"
                      + " 'clavaris_owner' AND NOT EXISTS (SELECT 1 FROM pg_depend d WHERE"
                      + " d.objid = p.oid AND d.deptype = 'e')"))
          .as("functions the migrations created")
          .isEmpty();
      assertThat(
              column(
                  connection,
                  "SELECT pg_get_userbyid(datdba) FROM pg_database WHERE"
                      + " datname = current_database()"))
          .containsExactly("clavaris_owner");
    }
  }

  // ---- what the application may do ----

  @Test
  void theApplicationCanReadAndWriteEveryTableExceptTheTwoItMustNotRewrite() throws Exception {
    try (Connection connection = superuser()) {
      final List<String> missing =
          column(
              connection,
              // By oid, from pg_class: a query on pg_tables may evaluate the privilege function on
              // a row of
              // another schema before it applies the schema filter, and fail on a catalog table.
              "SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
                  + " WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p')"
                  + " AND c.relname <> 'flyway_schema_history'"
                  + " AND c.relname NOT LIKE 'audit_events%'"
                  + " AND NOT (has_table_privilege('clavaris_app', c.oid, 'SELECT')"
                  + " AND has_table_privilege('clavaris_app', c.oid, 'INSERT')"
                  + " AND has_table_privilege('clavaris_app', c.oid, 'UPDATE')"
                  + " AND has_table_privilege('clavaris_app', c.oid, 'DELETE'))");
      assertThat(missing).as("tables the application cannot fully use").isEmpty();
      assertThat(
              column(
                  connection,
                  // OFFSET 0 keeps the planner from running the privilege function on an index
                  // before the filter on the kind has been applied.
                  "SELECT s.relname FROM (SELECT c.oid, c.relname FROM pg_class c JOIN"
                      + " pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public'"
                      + " AND c.relkind = 'S' OFFSET 0) s"
                      + " WHERE NOT has_sequence_privilege('clavaris_app', s.oid, 'USAGE')"))
          .isEmpty();
    }
  }

  @Test
  void aTableAMigrationCreatesLaterIsUsableWithoutAGrantOfItsOwn() throws Exception {
    try (Connection owner = owner()) {
      execute(owner, "CREATE TABLE later_table (id serial PRIMARY KEY, note text)");
    }
    try (Connection app = app()) {
      execute(app, "INSERT INTO later_table (note) VALUES ('written by the application')");
      execute(app, "UPDATE later_table SET note = 'changed'");
      assertThat(column(app, "SELECT note FROM later_table")).containsExactly("changed");
      execute(app, "DELETE FROM later_table");
    }
  }

  // ---- what it may not do ----

  @Test
  void theApplicationCannotChangeTheSchema() throws Exception {
    try (Connection connection = app()) {
      final String denied = "42501";
      assertThat(sqlState(() -> execute(connection, "CREATE TABLE sneaky (id int)")))
          .as("create a table")
          .isEqualTo(denied);
      assertThat(sqlState(() -> execute(connection, "ALTER TABLE audit_events ADD COLUMN x int")))
          .as("alter a table")
          .isEqualTo("42501");
      assertThat(sqlState(() -> execute(connection, "DROP TABLE audit_events_default")))
          .as("drop a partition by hand")
          .isEqualTo("42501");
      assertThat(sqlState(() -> execute(connection, "CREATE INDEX sneaky ON audit_events (id)")))
          .as("create an index")
          .isEqualTo("42501");
      assertThat(sqlState(() -> execute(connection, "CREATE EXTENSION IF NOT EXISTS hstore")))
          .as("install an extension")
          .isNotEqualTo("no error");
    }
  }

  @Test
  void theApplicationCannotReachTheHostThroughTheDatabase() throws Exception {
    try (Connection connection = app()) {
      assertThat(sqlState(() -> execute(connection, "COPY (SELECT 1) TO PROGRAM 'id'")))
          .as("run a program on the database host")
          .isEqualTo("42501");
      assertThat(sqlState(() -> execute(connection, "COPY (SELECT 1) TO '/tmp/x'")))
          .as("write a file on the database host")
          .isEqualTo("42501");
      assertThat(
              column(
                  connection,
                  "SELECT rolsuper::text FROM pg_roles WHERE rolname =" + " current_user"))
          .containsExactly("false");
      assertThat(sqlState(() -> execute(connection, "SELECT * FROM pg_authid")))
          .as("read the password hashes")
          .isEqualTo("42501");
      assertThat(sqlState(() -> execute(connection, "SET ROLE clavaris_owner")))
          .as("become the owner")
          .isEqualTo("42501");
      assertThat(sqlState(() -> execute(connection, "SELECT * FROM flyway_schema_history")))
          .as("read the migration history")
          .isEqualTo("42501");
    }
  }

  @Test
  void theMigrationRoleCannotBeUsedToSeeOrChangeRoles() throws Exception {
    try (Connection connection = owner()) {
      assertThat(sqlState(() -> execute(connection, "CREATE ROLE intruder"))).isEqualTo("42501");
      assertThat(sqlState(() -> execute(connection, "COPY (SELECT 1) TO PROGRAM 'id'")))
          .isEqualTo("42501");
    }
  }

  // ---- the audit log is append-only for the application ----

  @Test
  void theApplicationCanAppendToTheAuditLogAndReadItButNotRewriteIt() throws Exception {
    try (Connection connection = app()) {
      insertAuditEvent(connection, "2026-01-15 10:00:00+00");
      assertThat(column(connection, "SELECT count(*)::text FROM audit_events"))
          .isNotEqualTo(List.of("0"));
      assertThat(sqlState(() -> execute(connection, "UPDATE audit_events SET detail = 'edited'")))
          .as("rewrite an event")
          .isEqualTo("42501");
      assertThat(sqlState(() -> execute(connection, "DELETE FROM audit_events")))
          .as("delete an event")
          .isEqualTo("42501");
      assertThat(sqlState(() -> execute(connection, "TRUNCATE audit_events")))
          .as("truncate the log")
          .isEqualTo("42501");
    }
  }

  @Test
  void everyPartitionIsAppendOnlyForTheApplicationToo() throws Exception {
    try (Connection connection = superuser()) {
      assertThat(
              column(
                  connection,
                  "SELECT c.relname FROM pg_class c WHERE c.relname LIKE 'audit_events%'"
                      + " AND c.relkind IN ('r', 'p') AND (has_table_privilege('clavaris_app',"
                      + " c.oid, 'UPDATE') OR has_table_privilege('clavaris_app', c.oid,"
                      + " 'DELETE') OR has_table_privilege('clavaris_app', c.oid, 'TRUNCATE'))"))
          .as("the parent and the partitions the migrations created")
          .isEmpty();
    }
  }

  // ---- the two things it may do to the partitions, through the functions ----

  @Test
  void theApplicationCanCreateAPartitionAndItIsAppendOnlyAsWell() throws Exception {
    final LocalDate month = YearMonth.now(ZoneOffset.UTC).plusMonths(9).atDay(1);
    final String name = "audit_events_" + month.toString().substring(0, 7).replace('-', '_');
    try (Connection connection = app()) {
      assertThat(column(connection, "SELECT audit_events_ensure_partition('" + month + "')"))
          .containsExactly(name);
      assertThat(column(connection, "SELECT audit_events_ensure_partition('" + month + "')"))
          .as("idempotent")
          .containsExactly(name);
      insertAuditEvent(connection, month.plusDays(3) + " 12:00:00+00");
      assertThat(
              column(
                  connection,
                  "SELECT tableoid::regclass::text FROM audit_events WHERE occurred_at >= '"
                      + month
                      + "'::timestamptz"))
          .as("the event landed in the new partition, not the default one")
          .contains(name);
    }
    try (Connection connection = superuser()) {
      assertThat(
              column(
                  connection,
                  "SELECT pg_get_userbyid(relowner) FROM pg_class WHERE relname = '" + name + "'"))
          .containsExactly("clavaris_owner");
      assertThat(
              column(
                  connection,
                  "SELECT has_table_privilege('clavaris_app', '"
                      + name
                      + "', 'UPDATE')::text || has_table_privilege('clavaris_app', '"
                      + name
                      + "', 'DELETE')::text"))
          .containsExactly("falsefalse");
    }
  }

  @Test
  void theFunctionsRefuseWhatTheJobNeverAsksFor() throws Exception {
    final LocalDate thisMonth = YearMonth.now(ZoneOffset.UTC).atDay(1);
    try (Connection connection = app()) {
      assertThatThrownBy(
              () -> column(connection, "SELECT audit_events_drop_partition('" + thisMonth + "')"))
          .as("the current month is never dropped")
          .hasMessageContaining("never dropped");
      assertThatThrownBy(
              () ->
                  column(
                      connection,
                      "SELECT audit_events_drop_partition('" + thisMonth.plusMonths(2) + "')"))
          .as("neither is a later one")
          .hasMessageContaining("never dropped");
      assertThatThrownBy(
              () ->
                  column(connection, "SELECT audit_events_drop_partition('" + "1999-01-01" + "')"))
          .as("a month that has no partition is not 'dropped' by accident")
          .hasMessageContaining("is not a partition");
      assertThatThrownBy(
              () -> column(connection, "SELECT audit_events_ensure_partition('1990-01-01')"))
          .as("an absurd past month")
          .hasMessageContaining("outside");
      assertThatThrownBy(
              () -> column(connection, "SELECT audit_events_ensure_partition('2999-01-01')"))
          .as("an absurd future month")
          .hasMessageContaining("outside");
    }
  }

  @Test
  void theApplicationCanDropAnOldPartitionThroughTheFunction() throws Exception {
    final LocalDate old = YearMonth.now(ZoneOffset.UTC).minusMonths(30).atDay(1);
    final String name = "audit_events_" + old.toString().substring(0, 7).replace('-', '_');
    try (Connection connection = app()) {
      column(connection, "SELECT audit_events_ensure_partition('" + old + "')");
      assertThat(column(connection, "SELECT audit_events_drop_partition('" + old + "')"))
          .containsExactly(name);
    }
    try (Connection connection = superuser()) {
      assertThat(column(connection, "SELECT relname FROM pg_class WHERE relname = '" + name + "'"))
          .isEmpty();
    }
  }

  @Test
  void theDefaultPartitionCannotBeDroppedThroughTheFunction() throws Exception {
    // The function builds the name from a date, so audit_events_default is not reachable; the
    // closest thing to try is a date whose name would collide with nothing and is not a partition.
    try (Connection connection = app()) {
      assertThatThrownBy(
              () -> column(connection, "SELECT audit_events_drop_partition('2001-01-01')"))
          .hasMessageContaining("is not a partition");
    }
    try (Connection connection = superuser()) {
      assertThat(
              column(
                  connection,
                  "SELECT relname FROM pg_class WHERE relname = 'audit_events_default'"))
          .hasSize(1);
    }
  }

  // ---- running it again, and rotating a password ----

  @Test
  void runningTheScriptAgainChangesNothingAndARotatedPasswordIsApplied() throws Exception {
    final String rotated = "rotated-app-password-for-the-test-2";
    provision(OWNER_PASSWORD, rotated);
    try {
      assertThatThrownBy(() -> connectAs("clavaris_app", APP_PASSWORD))
          .as("the old password stops working")
          .isInstanceOf(SQLException.class);
      try (Connection connection = connectAs("clavaris_app", rotated)) {
        assertThat(column(connection, "SELECT current_user")).containsExactly("clavaris_app");
        assertThat(sqlState(() -> execute(connection, "CREATE TABLE still_denied (id int)")))
            .isEqualTo("42501");
      }
    } finally {
      provision(OWNER_PASSWORD, APP_PASSWORD);
    }
    try (Connection connection = app()) {
      assertThat(column(connection, "SELECT current_user")).containsExactly("clavaris_app");
    }
  }

  @Test
  void theScriptRefusesToRunWithoutAPassword() throws Exception {
    final ExecResult result =
        POSTGRES.execInContainer(
            "psql",
            "-X",
            "-q",
            "-v",
            "ON_ERROR_STOP=1",
            "-v",
            "owner_password=only-one-of-the-two",
            "-U",
            POSTGRES.getUsername(),
            "-d",
            POSTGRES.getDatabaseName(),
            "-f",
            "/tmp/provision-roles.sql");
    assertThat(result.getExitCode()).as("psql exit status").isNotZero();
    assertThat(result.getStderr()).contains("app_password is not set");
  }
}
