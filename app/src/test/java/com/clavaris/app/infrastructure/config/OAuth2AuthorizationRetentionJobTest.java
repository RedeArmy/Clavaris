package com.clavaris.app.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.app.support.RedisBackedIntegrationTest;
import com.clavaris.app.support.TestMailSenderConfig;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * TD-ARCH-005: proves the sweep keys off the *latest* of a row's expiry columns, not the first —
 * the exact distinction {@link OAuth2AuthorizationRetentionJob}'s own Javadoc explains (a row can
 * carry an authorization code, access token, and ID token, each with its own independent expiry).
 *
 * <p>{@code @Transactional} at the class level, same precedent {@code EventOutboxRetentionJobTest}
 * already establishes: each test method's own inserted rows roll back automatically once it
 * finishes, so one method's fixture rows never leak into another's row-count assertion — JUnit 5
 * doesn't guarantee declaration order between methods, so without this, whichever method happens to
 * run first leaves its own rows behind for the next one to (wrongly) count.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = "clavaris.oauth2-authorization.retention-grace-days=1")
@Import(TestMailSenderConfig.class)
@Transactional
class OAuth2AuthorizationRetentionJobTest extends RedisBackedIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Autowired private OAuth2AuthorizationRetentionJob job;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void sweepsARowOnlyOnceEveryExpiryColumnItCarriesHasPassedTheGraceWindow() {
    // Every column expired well past the grace window — swept.
    insertRow(
        Instant.now().minus(10, ChronoUnit.DAYS), Instant.now().minus(10, ChronoUnit.DAYS), null);
    // Access token expired long ago, but the ID token on the same row has not — kept, because
    // GREATEST(...) reflects the row's *latest* expiry, not its earliest.
    insertRow(
        Instant.now().minus(10, ChronoUnit.DAYS), Instant.now().plus(10, ChronoUnit.DAYS), null);
    // Only an authorization code column populated, already expired — swept.
    insertRow(null, null, Instant.now().minus(10, ChronoUnit.DAYS));
    // Recent, still inside the grace window — kept.
    insertRow(Instant.now().plus(1, ChronoUnit.HOURS), null, null);

    job.sweepExpiredRows();

    final Long remaining =
        jdbcTemplate.queryForObject("select count(*) from oauth2_authorization", Long.class);
    assertThat(remaining).isEqualTo(2L);
  }

  @Test
  void leavesEverythingAloneWhenNothingIsPastTheGraceWindow() {
    insertRow(Instant.now().plus(1, ChronoUnit.HOURS), null, null);
    insertRow(null, Instant.now().plus(1, ChronoUnit.HOURS), null);

    job.sweepExpiredRows();

    final Long remaining =
        jdbcTemplate.queryForObject("select count(*) from oauth2_authorization", Long.class);
    assertThat(remaining).isEqualTo(2L);
  }

  private void insertRow(
      final Instant accessTokenExpiresAt,
      final Instant oidcIdTokenExpiresAt,
      final Instant authorizationCodeExpiresAt) {
    jdbcTemplate.update(
        "insert into oauth2_authorization (id, registered_client_id, principal_name,"
            + " authorization_grant_type, access_token_expires_at, oidc_id_token_expires_at,"
            + " authorization_code_expires_at) values (?, ?, ?, ?, ?, ?, ?)",
        UUID.randomUUID().toString(),
        "some-client",
        "some-principal",
        "authorization_code",
        accessTokenExpiresAt == null ? null : Timestamp.from(accessTokenExpiresAt),
        oidcIdTokenExpiresAt == null ? null : Timestamp.from(oidcIdTokenExpiresAt),
        authorizationCodeExpiresAt == null ? null : Timestamp.from(authorizationCodeExpiresAt));
  }
}
