package com.clavaris.app.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.app.support.RedisBackedIntegrationTest;
import com.clavaris.app.support.TestMailSenderConfig;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * SDE-III review, 2026-09-03: real-Postgres proof that {@link OrphanedOAuthClientCleanupJob} both
 * removes a genuinely orphaned {@code OAuthClient} row (no matching {@code organizations} row at
 * all) and leaves an ordinary, still-owned one alone — the exact distinction its own Javadoc
 * describes (the RegisterOAuthClientService TOCTOU it backstops).
 *
 * <p>{@code @Transactional} at the class level, same precedent {@code
 * OAuth2AuthorizationRetentionJobTest} already establishes — each test method's own inserted rows
 * roll back automatically once it finishes.
 */
@SpringBootTest
@Testcontainers
@Import(TestMailSenderConfig.class)
@Transactional
class OrphanedOAuthClientCleanupJobTest extends RedisBackedIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Autowired private OrphanedOAuthClientCleanupJob job;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void removesAnOAuthClientWhoseOrganizationNoLongerExists() {
    UUID orphanedOrganizationId = UUID.randomUUID();
    insertOAuthClient(orphanedOrganizationId, "orphaned-client");

    job.sweepOrphanedClients();

    Long remaining =
        jdbcTemplate.queryForObject(
            "select count(*) from oauth_clients where client_id = ?",
            Long.class,
            "orphaned-client");
    assertThat(remaining).isZero();
  }

  @Test
  void leavesAnOAuthClientAloneWhenItsOrganizationStillExists() {
    UUID organizationId = UUID.randomUUID();
    insertOrganization(organizationId);
    insertOAuthClient(organizationId, "still-owned-client");

    job.sweepOrphanedClients();

    Long remaining =
        jdbcTemplate.queryForObject(
            "select count(*) from oauth_clients where client_id = ?",
            Long.class,
            "still-owned-client");
    assertThat(remaining).isEqualTo(1L);
  }

  private void insertOrganization(UUID organizationId) {
    jdbcTemplate.update(
        "insert into organizations (id, name, owner_platform_account_id) values (?, ?, ?)",
        organizationId,
        "Still Owned Co",
        UUID.randomUUID());
  }

  private void insertOAuthClient(UUID organizationId, String clientId) {
    jdbcTemplate.update(
        "insert into oauth_clients (id, organization_id, client_id, client_secret_hash,"
            + " redirect_uris, allowed_grant_types, allowed_scopes)"
            + " values (?, ?, ?, ?, ?, ?, ?)",
        UUID.randomUUID(),
        organizationId,
        clientId,
        "argon2id$hashed",
        "[\"https://example.com/callback\"]",
        "[\"authorization_code\"]",
        "[\"openid\"]");
  }
}
