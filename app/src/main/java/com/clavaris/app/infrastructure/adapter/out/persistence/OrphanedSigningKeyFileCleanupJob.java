package com.clavaris.app.infrastructure.adapter.out.persistence;

import com.clavaris.common.infrastructure.adapter.out.persistence.PostgresAdvisoryJobLock;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.infrastructure.adapter.out.security.OrganizationSigningKeyMaterialFactory;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * TD-SEC-102: removes the signing-key file of an Organization that no longer exists. Deleting an
 * Organization purges its file after the commit, from the list of kids it read beforehand, and that
 * list exists only in the running action: if the action fails (Redis down, a crash between the
 * commit and the purge) nothing remembers what to remove, and the Organization's private keys stay
 * on disk for good, which is what TD-SEC-052 exists to prevent. The file is the Organization's own
 * (TD-SEC-054), so this needs no list: any {@code signing-keys-org-<id>.p12} whose Organization is
 * gone is removed whole. Safe to repeat, and safe on several instances at once.
 *
 * <p>Only files older than {@link #MINIMUM_AGE} are considered: a new Organization's file is
 * written before the transaction that creates its row commits, and judging it in that moment would
 * delete live keys.
 */
@Component
class OrphanedSigningKeyFileCleanupJob {

  private static final Logger LOG = LoggerFactory.getLogger(OrphanedSigningKeyFileCleanupJob.class);

  /* package */ static final Duration MINIMUM_AGE = Duration.ofHours(1);

  private final OrganizationSigningKeyMaterialFactory keyMaterial;
  private final JdbcTemplate jdbcTemplate;
  private final PostgresAdvisoryJobLock jobLock;
  private final MeterRegistry meters;

  /* package */ OrphanedSigningKeyFileCleanupJob(
      final OrganizationSigningKeyMaterialFactory keyMaterial,
      final JdbcTemplate jdbcTemplate,
      final PostgresAdvisoryJobLock jobLock,
      final MeterRegistry meters) {
    this.meters = meters;
    this.keyMaterial = keyMaterial;
    this.jdbcTemplate = jdbcTemplate;
    this.jobLock = jobLock;
  }

  // Daily, off-peak (04:10 server time), a minute no other job uses (the schedule is in
  // deployment-runbook.md); one directory listing and one indexed lookup per file.
  @Scheduled(cron = "0 10 4 * * *")
  /* package */ void sweepOrphanedKeyFiles() {
    jobLock.runIfLockAcquired(
        "orphaned_signing_key_file_cleanup", LOG, this::sweepOrphanedKeyFilesLocked);
  }

  private void sweepOrphanedKeyFilesLocked() {
    final List<UUID> candidates = keyMaterial.organizationIdsWithKeyFiles(MINIMUM_AGE);
    int removed = 0;
    for (final UUID organizationId : candidates) {
      if (!organizationExists(organizationId)
          && keyMaterial.purgeFileFor(new OrganizationId(organizationId))) {
        removed++;
      }
    }
    if (removed > 0) {
      // TD-PROC-036: a file the sweep had to remove is one a purge missed.
      meters.counter("clavaris.signing_key.orphaned_files.removed").increment(removed);
      // Only the count, never which Organizations (BR-DATA-01), same convention as the other
      // sweeps.
      LOG.warn(
          "event=orphaned_signing_key_file_swept removedCount={}"
              + " reason=file_of_an_Organization_that_no_longer_exists",
          removed);
    }
  }

  private boolean organizationExists(final UUID organizationId) {
    return Boolean.TRUE.equals(
        jdbcTemplate.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM organizations WHERE id = ?)",
            Boolean.class,
            organizationId));
  }
}
