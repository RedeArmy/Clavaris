package com.clavaris.identity.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.clavaris.common.infrastructure.adapter.out.persistence.PostgresAdvisoryJobLock;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
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
 * Against a real Postgres: the sweep removes what has been expired for longer than the grace, keeps
 * what is inside it, and never breaks the refresh-token chain or a session still in use.
 */
@Testcontainers
class ExpiredIdentityRecordsRetentionJobTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  private static JdbcTemplate jdbc;
  private static PGSimpleDataSource dataSource;

  private ExpiredIdentityRecordsRetentionJob job;
  private UUID accountId;

  @BeforeAll
  static void connect() {
    dataSource = new PGSimpleDataSource();
    dataSource.setUrl(POSTGRES.getJdbcUrl());
    dataSource.setUser(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    jdbc = new JdbcTemplate(dataSource);
  }

  @BeforeEach
  void freshSchemaAndAnAccount() {
    Flyway flyway =
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .cleanDisabled(false)
            .load();
    flyway.clean();
    flyway.migrate();
    // BR-ID-02's deferred trigger wants a sign-in method for every new account; this test seeds
    // bare rows to exercise the sweep, so the trigger is switched off for its own schema only.
    jdbc.execute("ALTER TABLE accounts DISABLE TRIGGER trg_account_has_auth_method");
    accountId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO accounts (id, organization_id, email, status, created_at)"
            + " VALUES (?, ?, 'owner@example.com', 'ACTIVE', now())",
        accountId,
        UUID.randomUUID());
    job =
        new ExpiredIdentityRecordsRetentionJob(
            jdbc, mock(PostgresAdvisoryJobLock.class), 7, 2, 400);
  }

  private static Instant daysAgo(final int days) {
    return Instant.now().minus(days, ChronoUnit.DAYS);
  }

  private void verificationToken(final Instant expiresAt) {
    jdbc.update(
        "INSERT INTO verification_tokens (id, account_id, type, token_hash, expires_at)"
            + " VALUES (?, ?, 'EMAIL_VERIFICATION', ?, ?)",
        UUID.randomUUID(),
        accountId,
        UUID.randomUUID().toString().replace("-", ""),
        Timestamp.from(expiresAt));
  }

  // One pending request per account, so each request needs its own.
  private UUID anotherAccount() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO accounts (id, organization_id, email, status, created_at)"
            + " VALUES (?, ?, ?, 'ACTIVE', now())",
        id,
        UUID.randomUUID(),
        id + "@example.com");
    return id;
  }

  private void emailChangeRequest(final UUID id, final Instant expiresAt) {
    jdbc.update(
        "INSERT INTO email_change_requests (id, account_id, new_email, token_hash, expires_at)"
            + " VALUES (?, ?, 'n@example.com', ?, ?)",
        id,
        anotherAccount(),
        UUID.randomUUID().toString(),
        Timestamp.from(expiresAt));
  }

  private UUID session(final Instant lastSeen, final Instant revokedAt) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO sessions (id, account_id, scopes, created_at, last_seen_at, revoked_at)"
            + " VALUES (?, ?, 'openid', ?, ?, ?)",
        id,
        accountId,
        Timestamp.from(lastSeen),
        Timestamp.from(lastSeen),
        revokedAt == null ? null : Timestamp.from(revokedAt));
    return id;
  }

  private UUID refreshToken(final UUID sessionId, final UUID rotatedFrom, final Instant expiresAt) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO refresh_tokens (id, session_id, account_id, token_hash, rotated_from_id,"
            + " issued_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
        id,
        sessionId,
        accountId,
        UUID.randomUUID().toString().replace("-", "") + "ab",
        rotatedFrom,
        Timestamp.from(expiresAt.minus(30, ChronoUnit.DAYS)),
        Timestamp.from(expiresAt));
    return id;
  }

  private int count(final String table) {
    return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
  }

  @Test
  void removesTokensExpiredBeyondTheGraceAndKeepsTheRest() {
    verificationToken(daysAgo(30));
    verificationToken(daysAgo(10));
    verificationToken(daysAgo(3));
    verificationToken(Instant.now().plus(1, ChronoUnit.DAYS));
    UUID keptRequest = UUID.randomUUID();
    emailChangeRequest(UUID.randomUUID(), daysAgo(20));
    emailChangeRequest(keptRequest, daysAgo(1));

    Map<String, Integer> deleted = job.sweepLocked();

    assertThat(deleted)
        .containsEntry("verification_tokens", 2)
        .containsEntry("email_change_requests", 1);
    assertThat(count("verification_tokens")).isEqualTo(2);
    assertThat(jdbc.queryForList("SELECT id FROM email_change_requests", UUID.class))
        .containsExactly(keptRequest);
  }

  @Test
  void aBacklogLargerThanTheBatchIsClearedInSeveralBatches() {
    for (int i = 0; i < 5; i++) {
      verificationToken(daysAgo(40));
    }

    assertThat(job.sweepLocked()).containsEntry("verification_tokens", 5);
    assertThat(count("verification_tokens")).isZero();
  }

  @Test
  void aRotatedParentIsKeptWhileALiveSuccessorStillPointsAtIt() {
    UUID session = session(daysAgo(1), null);
    UUID parent = refreshToken(session, null, daysAgo(10));
    UUID liveChild = refreshToken(session, parent, Instant.now().plus(20, ChronoUnit.DAYS));

    job.sweepLocked();

    assertThat(jdbc.queryForList("SELECT id FROM refresh_tokens", UUID.class))
        .containsExactlyInAnyOrder(parent, liveChild);
  }

  @Test
  void anEntirelyExpiredChainGoesTogetherDespiteTheForeignKey() {
    UUID session = session(daysAgo(60), daysAgo(40));
    UUID parent = refreshToken(session, null, daysAgo(45));
    refreshToken(session, parent, daysAgo(40));

    Map<String, Integer> deleted = job.sweepLocked();

    assertThat(deleted).containsEntry("refresh_tokens", 2).containsEntry("sessions", 1);
    assertThat(count("refresh_tokens")).isZero();
    assertThat(count("sessions")).isZero();
  }

  @Test
  void aSessionStillReferencedByATokenOrRecentlySeenIsKept() {
    UUID stillHasToken = session(daysAgo(60), daysAgo(40));
    refreshToken(stillHasToken, null, Instant.now().plus(5, ChronoUnit.DAYS));
    UUID recentlyRevoked = session(daysAgo(60), daysAgo(2));
    UUID active = session(daysAgo(1), null);

    job.sweepLocked();

    assertThat(jdbc.queryForList("SELECT id FROM sessions", UUID.class))
        .containsExactlyInAnyOrder(stillHasToken, recentlyRevoked, active);
  }

  private void loginEvent(final Instant occurredAt) {
    jdbc.update(
        "INSERT INTO login_events (id, account_id, organization_id, occurred_at)"
            + " VALUES (?, ?, ?, ?)",
        UUID.randomUUID(),
        accountId,
        UUID.randomUUID(),
        Timestamp.from(occurredAt));
  }

  @Test
  void signInHistoryOlderThanTheWindowGoesAndRecentHistoryStays() {
    loginEvent(daysAgo(500));
    loginEvent(daysAgo(401));
    loginEvent(daysAgo(399));
    loginEvent(daysAgo(1));

    Map<String, Integer> deleted = job.sweepLocked();

    assertThat(deleted).containsEntry("login_events", 2);
    assertThat(count("login_events")).isEqualTo(2);
  }

  @Test
  void aWindowOfZeroKeepsSignInHistoryForever() {
    ExpiredIdentityRecordsRetentionJob keepForever =
        new ExpiredIdentityRecordsRetentionJob(jdbc, mock(PostgresAdvisoryJobLock.class), 7, 2, 0);
    loginEvent(daysAgo(5000));

    assertThat(keepForever.sweepLocked()).doesNotContainKey("login_events");
    assertThat(count("login_events")).isEqualTo(1);
  }
}
