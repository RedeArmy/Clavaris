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
}
