package com.clavaris.app.infrastructure.config;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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
// PMD.LongVariable: DELETE_EXPIRED_SQL/retentionGraceDays both name exactly what they are — a
// shortened identifier would only make this class harder to read, same convention every other
// descriptively-named port/constant in this codebase follows (e.g. PlatformScopes' own class-wide
// suppression for the identical reason).
@SuppressWarnings("PMD.LongVariable")
@Component
class OAuth2AuthorizationRetentionJob {

  private static final Logger LOG = LoggerFactory.getLogger(OAuth2AuthorizationRetentionJob.class);

  private static final String DELETE_EXPIRED_SQL =
      """
      DELETE FROM oauth2_authorization
      WHERE GREATEST(
        authorization_code_expires_at,
        access_token_expires_at,
        oidc_id_token_expires_at,
        refresh_token_expires_at,
        user_code_expires_at,
        device_code_expires_at
      ) < ?
      """;

  private final JdbcTemplate jdbcTemplate;
  private final int retentionGraceDays;

  // Constructed only by Spring's own component scan (via @Component above).
  /* package */ OAuth2AuthorizationRetentionJob(
      final JdbcTemplate jdbcTemplate,
      @Value("${clavaris.oauth2-authorization.retention-grace-days:1}")
          final int retentionGraceDays) {
    this.jdbcTemplate = jdbcTemplate;
    this.retentionGraceDays = retentionGraceDays;
  }

  // Daily, off-peak (03:45 server time) — 15 minutes after EventOutboxRetentionJob's own 03:30
  // slot, so the two never contend for the same window; both are cheap, index-backed deletes on
  // tables with no other scheduled writer at that hour.
  @Scheduled(cron = "0 45 3 * * *")
  @Transactional
  /* package */ void sweepExpiredRows() {
    final Instant cutoff = Instant.now().minus(retentionGraceDays, ChronoUnit.DAYS);
    final int deleted = jdbcTemplate.update(DELETE_EXPIRED_SQL, java.sql.Timestamp.from(cutoff));
    if (deleted > 0) {
      LOG.info(
          "event=oauth2_authorization_retention_swept deletedCount={} retentionGraceDays={}",
          deleted,
          retentionGraceDays);
    }
  }
}
