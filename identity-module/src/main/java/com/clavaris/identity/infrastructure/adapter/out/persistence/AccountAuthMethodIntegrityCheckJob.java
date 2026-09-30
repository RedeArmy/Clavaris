package com.clavaris.identity.infrastructure.adapter.out.persistence;

import com.clavaris.common.infrastructure.adapter.out.persistence.PostgresAdvisoryJobLock;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * BR-ID-02 ("never zero auth methods") — <b>secondary</b> safety net as of migration {@code
 * V20260830110000} (SDE-III design, Phase 2 #8): the primary enforcement is now {@code
 * trg_account_has_auth_method}/{@code trg_platform_account_has_auth_method}, a {@code DEFERRABLE
 * INITIALLY DEFERRED} Postgres constraint trigger that fires at transaction commit — real,
 * synchronous, transactional rejection of the whole insert, not a same-day-eventual alarm. See that
 * migration's own comment for why a deferred trigger, not a mid-transaction application-layer
 * guard, is what actually works here (the ordering problem {@link JpaAccountRepository#save}'s own
 * comment documents: the FK on {@code social_identities.account_id} forces the accounts row to
 * exist first, so a check at accounts-insert time can never see the same transaction's own
 * not-yet-run identity insert).
 *
 * <p>This job stays wired regardless — a second, independent detector that a future raw-SQL admin
 * script bypassing the trigger, or a migration that somehow drops it, would not otherwise surface
 * until an affected {@code Account}'s owner discovers they can never actually authenticate.
 * Deliberately logs only the count, never which accounts (BR-DATA-01) — an operator who sees a
 * non-zero count investigates via a direct DB query; this job's own job is only "raise the alarm."
 */
@Component
class AccountAuthMethodIntegrityCheckJob {

  private static final Logger LOG =
      LoggerFactory.getLogger(AccountAuthMethodIntegrityCheckJob.class);

  // TD-PERF-001: bounds each chunk's own two queries (id-page fetch, orphan count within it) to a
  // predictable size regardless of total account-count-system-wide — see
  // SpringDataAccountJpaRepository#findAccountIdsForIntegrityCheckChunk's own Javadoc.
  private static final int CHUNK_SIZE = 1000;

  private final SpringDataAccountJpaRepository accounts;
  private final PostgresAdvisoryJobLock jobLock;

  // Constructed only by Spring's own component scan (via @Component above).
  /* package */ AccountAuthMethodIntegrityCheckJob(
      final SpringDataAccountJpaRepository accounts, final PostgresAdvisoryJobLock jobLock) {
    this.accounts = accounts;
    this.jobLock = jobLock;
  }

  // Daily, off-peak (03:45 — 15 minutes after EventOutboxRetentionJob's own 03:30 slot, same "no
  // other scheduled job to coordinate against yet" reasoning that job's own Javadoc documents,
  // just staggered rather than colliding).
  //
  // TD-FUT-033: guarded by PostgresAdvisoryJobLock — see that class's own Javadoc. A second
  // instance racing this exact tick simply skips it rather than logging the same orphan count
  // twice.
  @Scheduled(cron = "0 45 3 * * *")
  /* package */ void checkForOrphanedAccounts() {
    jobLock.runIfLockAcquired(
        "account_auth_method_integrity_check", LOG, this::checkForOrphanedAccountsLocked);
  }

  // TD-PERF-001 (closed): chunked, not one unscoped full-table scan — see
  // SpringDataAccountJpaRepository#findAccountIdsForIntegrityCheckChunk's own Javadoc. A chunk
  // smaller than CHUNK_SIZE means it was the last one; afterId seeks past the last id each page
  // already returned, so every account is covered exactly once across however many chunks the
  // current account-count-system-wide needs.
  private void checkForOrphanedAccountsLocked() {
    long totalOrphanCount = 0;
    UUID afterId = null;
    List<UUID> chunkIds;
    do {
      chunkIds = accounts.findAccountIdsForIntegrityCheckChunk(afterId, CHUNK_SIZE);
      if (!chunkIds.isEmpty()) {
        totalOrphanCount += accounts.countOrphansAmongIds(chunkIds);
        afterId = chunkIds.get(chunkIds.size() - 1);
      }
    } while (chunkIds.size() == CHUNK_SIZE);

    if (totalOrphanCount > 0) {
      LOG.warn(
          "event=account_auth_method_integrity_violation orphanCount={} "
              + "reason=BR-ID-02_never_zero_auth_methods",
          totalOrphanCount);
    }
  }
}
