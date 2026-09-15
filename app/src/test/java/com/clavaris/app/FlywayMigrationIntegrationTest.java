package com.clavaris.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.app.support.RedisBackedIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves data-model.md §4's claim in CI, not just by manual docker-compose verification: Flyway's
 * migrations apply cleanly against a real Postgres, and Hibernate's ddl-auto=validate agrees with
 * the result, on every build. A failed migration, or an entity that doesn't match what the
 * migrations actually created, already fails the context load itself — the assertions below
 * additionally pin down *why* it loaded (SonarCloud S2699: a test with no assertion only proves
 * "nothing threw," not the specific claim in the class Javadoc above).
 *
 * <p>Extends {@link RedisBackedIntegrationTest} (TD-ARCH-002 regression, found live in CI, not
 * locally): {@code DistributedSessionConfig}'s {@code @EnableRedisIndexedHttpSession} registers
 * {@code RedisIndexedHttpSessionConfiguration$EnableRedisKeyspaceNotificationsInitializer}, which
 * eagerly opens a real Redis connection during context startup for every {@code @SpringBootTest}
 * that boots the full app context — not only ones that exercise sessions. Locally this was masked
 * the exact same way {@link RedisBackedIntegrationTest}'s own Javadoc already warns about for the
 * rate-limiting filters: a real Redis happens to already be listening on {@code localhost:6379} via
 * {@code docker-compose.yml}. CI has no such container, so the context failed to start at all.
 */
@SpringBootTest
@Testcontainers
class FlywayMigrationIntegrationTest extends RedisBackedIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Autowired private Flyway flyway;

  @Test
  void applicationContextLoadsWithMigrationsApplied() {
    // Deliberately not asserting an exact count/version: that would make this test brittle
    // against every future migration added under db/migration (it would need editing on every
    // such change for a fact it doesn't actually need to know). What must always hold, regardless
    // of how many migrations exist, is: at least one ran, every one that ran succeeded, and none
    // were left pending once the context finished starting.
    assertThat(flyway.info().applied())
        .as("Flyway must have applied at least one migration against the container")
        .isNotEmpty();
    assertThat(flyway.info().applied())
        .as("every applied migration must be in a successful, non-failed state")
        .allSatisfy(migration -> assertThat(migration.getState().isApplied()).isTrue());
    assertThat(flyway.info().pending())
        .as("nothing should be left un-applied once the context has finished starting")
        .isEmpty();
  }

  // SDE-III review, 2026-09-15 — real deployment-failure risk found and closed: every migration
  // across all 6 modules' own db/migration folders (merged into one shared Postgres schema/history
  // in the real app, same "one shared database" reasoning OrganizationEventOutboxEntity's own
  // Javadoc already documents) uses the VYYYYMMDDHHmmss timestamp scheme, except
  // V1__enable_pgcrypto.sql - a lone survivor from before that convention existed. A future
  // migration mistakenly following V1's own sequential-looking precedent (V2__..., V3__...) would
  // sort BELOW whatever timestamp-versioned migration was applied most recently against a real,
  // already-migrated database - Flyway's default outOfOrder=false then refuses to apply it at all,
  // a deployment failure discovered live, not in CI. This test enforces the real convention at
  // build time instead: every migration's own version must match the timestamp scheme, except this
  // one, permanently allow-listed exception (see that migration's own comment for why it can never
  // be safely renamed to fit the pattern after the fact).
  @Test
  void everyMigrationVersionFollowsTheTimestampSchemeExceptTheDocumentedV1Exception() {
    final String grandfatheredVersion = "1";
    final String timestampVersionPattern = "\\d{14}";
    assertThat(flyway.info().all())
        .extracting(migration -> migration.getVersion().getVersion())
        .filteredOn(version -> !grandfatheredVersion.equals(version))
        .as(
            "every migration except the documented V1 baseline must use the VYYYYMMDDHHmmss"
                + " scheme - a bare sequential version like V2 risks a real out-of-order"
                + " deployment failure against an already-migrated database")
        .allSatisfy(version -> assertThat(version).matches(timestampVersionPattern));
  }
}
