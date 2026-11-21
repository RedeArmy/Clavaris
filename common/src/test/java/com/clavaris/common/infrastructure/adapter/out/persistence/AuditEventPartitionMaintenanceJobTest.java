package com.clavaris.common.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.clavaris.common.application.port.AuditEventRecorder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * TD-FUT-047, against a real Postgres: the migration that partitions {@code audit_events} keeps
 * every row, rows land in the partition of their month, and the maintenance job creates the months
 * ahead and (only when asked to) drops the old ones.
 */
@Testcontainers
class AuditEventPartitionMaintenanceJobTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

  private static PGSimpleDataSource dataSource;
  private static JdbcTemplate jdbc;

  @BeforeAll
  static void migrate() {
    dataSource = new PGSimpleDataSource();
    dataSource.setUrl(POSTGRES.getJdbcUrl());
    dataSource.setUser(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    jdbc = new JdbcTemplate(dataSource);
  }

  // Every test starts from an empty, fully migrated schema: the tests create and drop partitions,
  // and none may depend on what another left behind.
  @BeforeEach
  void freshSchema() {
    Flyway flyway = flyway().cleanDisabled(false).load();
    flyway.clean();
    flyway.migrate();
  }

  // TD-TEST-015: the application's own Flyway lock setting (transactional-lock: false, see
  // application.yml) does not reach a Flyway built by hand, and the default lock keeps a
  // transaction open that a CREATE INDEX CONCURRENTLY migration then waits on forever.
  private static FluentConfiguration flyway() {
    return Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .configuration(Map.of("flyway.postgresql.transactional.lock", "false"));
  }

  private static void createPartition(final String month, final String from, final String to) {
    jdbc.execute(
        "CREATE TABLE IF NOT EXISTS audit_events_"
            + month
            + " PARTITION OF audit_events FOR VALUES FROM ('"
            + from
            + " 00:00:00+00') TO ('"
            + to
            + " 00:00:00+00')");
  }

  private static void insert(final UUID id, final String occurredAt) {
    jdbc.update(
        "INSERT INTO audit_events (id, actor_type, actor_id, action, target_type, target_id,"
            + " occurred_at) VALUES (?, 'PLATFORM_CLIENT', 'c', 'test.action', 'Organization', ?,"
            + " ?::timestamptz)",
        id,
        id.toString(),
        occurredAt);
  }

  private static List<String> partitions() {
    return jdbc.queryForList(
        "SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid"
            + " WHERE i.inhparent = 'audit_events'::regclass ORDER BY 1",
        String.class);
  }

  private AuditEventPartitionMaintenanceJob job(
      final String today, final int retentionMonths, final AuditEventRecorder recorder) {
    PostgresAdvisoryJobLock lock = mock(PostgresAdvisoryJobLock.class);
    doAnswer(
            invocation -> {
              invocation.getArgument(2, Runnable.class).run();
              return null;
            })
        .when(lock)
        .runIfLockAcquired(any(), any(), any());
    return new AuditEventPartitionMaintenanceJob(
        jdbc,
        recorder,
        lock,
        registry,
        retentionMonths,
        3,
        Clock.fixed(Instant.parse(today + "T12:00:00Z"), ZoneOffset.UTC));
  }

  // The data-preservation proof a migration that rewrites a table owes: rows written before it, old
  // and recent, are all there afterwards, each in the partition of its month.
  @Test
  void theMigrationKeepsEveryRowAndFilesEachInItsMonth() {
    flyway().cleanDisabled(false).load().clean();
    flyway().target("20261026110000").load().migrate();
    UUID old = UUID.randomUUID();
    UUID recent = UUID.randomUUID();
    insert(old, "2025-03-15T10:00:00Z");
    insert(recent, "2026-10-20T10:00:00Z");

    flyway().load().migrate();

    assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events", Integer.class))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT tableoid::regclass::text FROM audit_events WHERE id = ?",
                String.class,
                old))
        .isEqualTo("audit_events_2025_03");
    assertThat(
            jdbc.queryForObject(
                "SELECT tableoid::regclass::text FROM audit_events WHERE id = ?",
                String.class,
                recent))
        .isEqualTo("audit_events_2026_10");
    assertThat(partitions()).contains("audit_events_default", "audit_events_2025_03");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM pg_tables WHERE tablename = 'audit_events_unpartitioned'",
                Integer.class))
        .as("the old table is gone")
        .isZero();
  }

  // TD-ARCH-048: a month with no partition used to be a WARN line; it is now a gauge an alert
  // reads.
  @Test
  void reportsWhetherTheDefaultPartitionHoldsRows() {
    AuditEventPartitionMaintenanceJob maintenance =
        job("2027-02-10", 0, mock(AuditEventRecorder.class));
    assertThat(defaultPartitionInUse()).as("before the first check").isEqualTo(-1.0);

    maintenance.maintainLocked();
    assertThat(defaultPartitionInUse()).as("nothing outside a partition").isZero();

    insert(UUID.randomUUID(), "2000-01-15T00:00:00Z");
    maintenance.maintainLocked();
    assertThat(defaultPartitionInUse()).as("a row no month claims").isEqualTo(1.0);
  }

  private double defaultPartitionInUse() {
    return registry.get("clavaris.audit.events.default.partition.in.use").gauge().value();
  }

  @Test
  void createsTheMonthsAheadAndIsIdempotent() {
    AuditEventPartitionMaintenanceJob maintenance =
        job("2027-02-10", 0, mock(AuditEventRecorder.class));

    maintenance.maintainLocked();
    maintenance.maintainLocked();

    assertThat(partitions())
        .contains(
            "audit_events_2027_02",
            "audit_events_2027_03",
            "audit_events_2027_04",
            "audit_events_2027_05");
    UUID id = UUID.randomUUID();
    insert(id, "2027-04-30T23:59:59Z");
    assertThat(
            jdbc.queryForObject(
                "SELECT tableoid::regclass::text FROM audit_events WHERE id = ?", String.class, id))
        .isEqualTo("audit_events_2027_04");
  }

  @Test
  void keepsEverythingWhenRetentionIsOff() {
    createPartition("2025_03", "2025-03-01", "2025-04-01");
    AuditEventRecorder recorder = mock(AuditEventRecorder.class);
    job("2030-01-10", 0, recorder).maintainLocked();

    assertThat(partitions()).contains("audit_events_2025_03");
    verify(recorder, never()).write(any(), any(), any(), any(), any());
  }

  // TD-SEC-106: audit_events_drop_partition refuses the current and any later month by the
  // database's
  // own clock, whatever the job's clock says, so these two tests run on the real month. A job whose
  // clock was years ahead would ask for a drop the function refuses, which is what it is there for.
  private static YearMonth thisMonth() {
    return YearMonth.now(ZoneOffset.UTC);
  }

  private static String today() {
    return LocalDate.now(ZoneOffset.UTC).toString();
  }

  private static String suffix(final YearMonth month) {
    return String.format("%04d_%02d", month.getYear(), month.getMonthValue());
  }

  private static void createPartition(final YearMonth month) {
    createPartition(
        suffix(month), month.atDay(1).toString(), month.plusMonths(1).atDay(1).toString());
  }

  @Test
  void dropsOnlyPartitionsWhollyOlderThanTheRetentionAndRecordsEach() {
    YearMonth ancient = thisMonth().minusMonths(40);
    YearMonth old = thisMonth().minusMonths(30);
    YearMonth recent = thisMonth().minusMonths(3);
    createPartition(ancient);
    createPartition(old);
    createPartition(recent);
    AuditEventRecorder recorder = mock(AuditEventRecorder.class);

    // 24 months of retention keeps from two years ago; the 40 and the 30 month old partitions are
    // expired, the one from three months ago is not.
    job(today(), 24, recorder).maintainLocked();

    assertThat(partitions())
        .doesNotContain("audit_events_" + suffix(ancient), "audit_events_" + suffix(old))
        .contains(
            "audit_events_default",
            "audit_events_" + suffix(recent),
            "audit_events_" + suffix(thisMonth()),
            "audit_events_" + suffix(thisMonth().plusMonths(3)));
    verify(recorder)
        .write(
            any(),
            eq("audit_log.partition_dropped"),
            eq("AuditLog"),
            eq("audit_events_" + suffix(ancient)),
            any());
    verify(recorder)
        .write(
            any(),
            eq("audit_log.partition_dropped"),
            eq("AuditLog"),
            eq("audit_events_" + suffix(old)),
            any());
  }

  @Test
  void neverDropsTheCurrentOrAFutureMonthWhateverTheRetention() {
    YearMonth previous = thisMonth().minusMonths(1);
    createPartition(previous);
    job(today(), 1, mock(AuditEventRecorder.class)).maintainLocked();

    assertThat(partitions())
        .contains(
            "audit_events_" + suffix(previous),
            "audit_events_" + suffix(thisMonth()),
            "audit_events_" + suffix(thisMonth().plusMonths(1)),
            "audit_events_" + suffix(thisMonth().plusMonths(3)));
  }
}
