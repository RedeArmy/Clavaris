package com.clavaris.app;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves data-model.md §4's claim in CI, not just by manual docker-compose
 * verification: Flyway's migrations apply cleanly against a real Postgres,
 * and Hibernate's ddl-auto=validate agrees with the result, on every build.
 * A failed migration, or an entity that doesn't match what the migrations
 * actually created, fails this context load — nothing else needs asserting.
 */
@SpringBootTest
@Testcontainers
class FlywayMigrationIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Test
    void applicationContextLoadsWithMigrationsApplied() {
        // Intentionally empty — the assertion is a successful @SpringBootTest
        // bootstrap. See test-strategy.md §2 ("Integration | Persistence
        // adapters against a real Postgres (Testcontainers)").
    }
}
