package com.clavaris.app.infrastructure.adapter.out.persistence;

import com.clavaris.app.infrastructure.adapter.out.security.RefreshTokenRotationAuthenticationProvider;
import com.clavaris.common.infrastructure.adapter.out.persistence.PostgresAdvisoryJobLock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * TD-ARCH-005: bounds {@code oauth2_authorization} growth. {@link
 * RefreshTokenRotationAuthenticationProvider}'s own Javadoc already documents why this table needs
 * a sweep: every refresh-token rotation saves a brand-new row (a fresh random id) rather than
 * updating the previous grant's row in place the way Spring Authorization Server's own stock
 * provider does — a deliberate consequence of keeping refresh-token validation fully decoupled from
 * this table (BR-ID-03, TD-SEC-003/TD-SEC-019's own security reasoning). That decoupling is worth
 * keeping; reintroducing a lookup of the prior row here to update-in-place would undo it. A
 * retention sweep is the fix that doesn't touch that boundary at all — same "cheap,
 * well-precedented cleanup job" shape as {@code EventOutboxRetentionJob}/{@code
 * OrganizationEventOutboxRetentionJob} (TD-TEST-002), applied to a table this codebase doesn't own
 * the schema of (Spring Authorization Server's own {@code JdbcOAuth2AuthorizationService} does, per
 * this table's own migration comment) — plain {@link JdbcTemplate}, not a Spring Data
 * repository/entity, since there is no domain model here to map to.
 *
 * <p><b>Why "every expiry column, not just one":</b> a single row can carry an authorization code,
 * an access token, and an OIDC ID token, each with its own {@code *_expires_at} column (refresh
 * token/user code/device code columns are never populated by this codebase — {@link
 * HashedTokenOAuth2AuthorizationService}'s own Javadoc). A row is only safe to delete once every
 * token it ever carried has expired — deleting on the *first* expiring column would discard a row
 * whose access token already expired but whose ID token (issued at the same instant, same TTL
 * today, but not guaranteed to stay that way) technically has not. {@code GREATEST(...)} across all
 * six expiry columns, which PostgreSQL evaluates ignoring {@code NULL} arguments unless every
 * argument is {@code NULL}, is exactly "the latest expiry this row ever carried."
 *
 * <p>{@code retentionGraceDays} adds a buffer past the last expiry, not zero — matches this
 * codebase's own "don't sweep the instant something goes stale" convention ({@code
 * EventOutboxRetentionJob}'s own 90-day default), here deliberately much shorter (1 day) since an
 * expired access/ID token is not awaiting delivery to anyone the way an unpublished outbox event is
 * — there is no dispatcher-lag reason to hold onto it, only a short buffer against clock skew
 * between this instance and Postgres.
 */
@Component
class OAuth2AuthorizationRetentionJob {

  private static final Logger LOG = LoggerFactory.getLogger(OAuth2AuthorizationRetentionJob.class);

  // TD-PERF-060: a batch at a time. The inner SELECT is the part the expression index
  // ix_oauth2_authorization_latest_expiry serves (same GREATEST, same column order); a single
  // DELETE over a table with a row per grant was one long lock, one WAL burst and one vacuum
  // backlog.
  /* package */ static final String DELETE_EXPIRED_BATCH_SQL =
      """
      DELETE FROM oauth2_authorization
      WHERE id IN (
        SELECT id FROM oauth2_authorization
        WHERE GREATEST(
          authorization_code_expires_at,
          access_token_expires_at,
          oidc_id_token_expires_at,
          refresh_token_expires_at,
          user_code_expires_at,
          device_code_expires_at
        ) < ?
        LIMIT ?
      )
      """;

  private final JdbcTemplate jdbcTemplate;
  private final int retentionGraceDays;
  private final int batchSize;
  private final PostgresAdvisoryJobLock jobLock;

  // Constructed only by Spring's own component scan (via @Component above).
  /* package */ OAuth2AuthorizationRetentionJob(
      final JdbcTemplate jdbcTemplate,
      @Value("${clavaris.oauth2-authorization.retention-grace-days:1}")
          final int retentionGraceDays,
      @Value("${clavaris.oauth2-authorization.retention-batch-size:5000}") final int batchSize,
      final PostgresAdvisoryJobLock jobLock) {
    this.jdbcTemplate = jdbcTemplate;
    this.retentionGraceDays = retentionGraceDays;
    this.batchSize = batchSize;
    this.jobLock = jobLock;
  }

  // Daily, off-peak (03:45 server time) — 15 minutes after EventOutboxRetentionJob's own 03:30
  // slot, so the two never contend for the same window.
  //
  // TD-FUT-033: guarded by PostgresAdvisoryJobLock.
  //
  // TD-PERF-060: no @Transactional here any more. Each batch is its own statement and commits on
  // its
  // own, so a night's sweep is many short transactions instead of one long one, and a crash keeps
  // what was already swept.
  @Scheduled(cron = "0 45 3 * * *")
  /* package */ void sweepExpiredRows() {
    jobLock.runIfLockAcquired("oauth2_authorization_retention", LOG, this::sweepExpiredRowsLocked);
  }

  /* package */ void sweepExpiredRowsLocked() {
    final OffsetDateTime cutoff =
        OffsetDateTime.ofInstant(
            Instant.now().minus(retentionGraceDays, ChronoUnit.DAYS), ZoneOffset.UTC);
    long deleted = 0;
    int batch;
    do {
      batch = jdbcTemplate.update(DELETE_EXPIRED_BATCH_SQL, cutoff, batchSize);
      deleted += batch;
    } while (batch == batchSize);
    if (deleted > 0) {
      LOG.info(
          "event=oauth2_authorization_retention_swept deletedCount={} retentionGraceDays={}",
          deleted,
          retentionGraceDays);
    }
  }
}
