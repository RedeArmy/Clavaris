package com.clavaris.app.infrastructure.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * SDE-III review, 2026-09-03 — real bug found and closed: {@code
 * RegisterOAuthClientService.handle()} checks {@code OrganizationExistsChecker.exists(...)}, then
 * saves the new {@code OAuthClient} row, as two separate, non-atomic operations — {@code
 * oauth_clients.organization_id} has no FK to {@code organizations} (that table's own migration
 * comment explains why: cross-module Flyway ordering between client-registry-module and
 * organization-module is not guaranteed, so a real FK isn't safely addable, same reasoning {@code
 * signing_keys}' own migration already documents). A concurrent {@code DeleteOrganizationService}
 * call racing between the exists-check and the save leaves a genuinely orphaned {@code OAuthClient}
 * row behind permanently — no owning {@code Organization} exists to ever reach or clean it up
 * through the normal delete flow again.
 *
 * <p>Deliberately a real, self-healing sweep (deletes, not just alarms) — unlike {@code
 * AccountAuthMethodIntegrityCheckJob}'s own log-only stance (there, deleting could destroy a real
 * {@code Account} the trigger just hasn't caught up to yet), an {@code oauth_clients} row whose
 * {@code organization_id} matches no {@code organizations} row at all is unconditionally dead
 * weight: {@code Organization} is never soft-deleted (BR-DATA-02/03's hard-delete philosophy), so
 * there is no "it might come back" case to preserve against. Plain {@link JdbcTemplate}, not a
 * Spring Data repository — this is a genuine cross-module query (this codebase's own module
 * independence rule means neither client-registry-module nor organization-module may depend on the
 * other's repository type), same "app is the one module allowed to see both tables" reasoning
 * {@code OrganizationExistsCheckerBridge} already establishes for the synchronous check this job
 * backstops.
 */
// PMD.LongVariable: DELETE_ORPHANED_SQL names exactly what it is — same convention
// OAuth2AuthorizationRetentionJob's own identical suppression already documents for its sibling
// DELETE_EXPIRED_SQL constant.
@SuppressWarnings("PMD.LongVariable")
@Component
class OrphanedOAuthClientCleanupJob {

  private static final Logger LOG = LoggerFactory.getLogger(OrphanedOAuthClientCleanupJob.class);

  private static final String DELETE_ORPHANED_SQL =
      """
      DELETE FROM oauth_clients c
      WHERE NOT EXISTS (SELECT 1 FROM organizations o WHERE o.id = c.organization_id)
      """;

  private final JdbcTemplate jdbcTemplate;

  // Constructed only by Spring's own component scan (via @Component above).
  /* package */ OrphanedOAuthClientCleanupJob(final JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  // Daily, off-peak (03:50 server time) — staggered 5/20 minutes after
  // OAuth2AuthorizationRetentionJob (03:45) / EventOutboxRetentionJob (03:30) so none contend for
  // the same window; a cheap, index-backed (ux_oauth_clients_client_id, small table) delete.
  @Scheduled(cron = "0 50 3 * * *")
  @Transactional
  /* package */ void sweepOrphanedClients() {
    final int deleted = jdbcTemplate.update(DELETE_ORPHANED_SQL);
    if (deleted > 0) {
      // Deliberately logs only the count, never which clients (BR-DATA-01) — same convention
      // AccountAuthMethodIntegrityCheckJob's own identical logging already follows; an operator
      // who sees a non-zero count can investigate via a direct DB query if they need to know more.
      LOG.warn(
          "event=orphaned_oauth_client_cleanup_swept deletedCount={} "
              + "reason=organization_id_referenced_no_existing_Organization",
          deleted);
    }
  }
}
