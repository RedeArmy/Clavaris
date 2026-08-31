package com.clavaris.identity.infrastructure.adapter.out.persistence;

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

  private final SpringDataAccountJpaRepository accounts;

  // Constructed only by Spring's own component scan (via @Component above).
  /* package */ AccountAuthMethodIntegrityCheckJob(final SpringDataAccountJpaRepository accounts) {
    this.accounts = accounts;
  }

  // Daily, off-peak (03:45 — 15 minutes after EventOutboxRetentionJob's own 03:30 slot, same "no
  // other scheduled job to coordinate against yet" reasoning that job's own Javadoc documents,
  // just staggered rather than colliding).
  @Scheduled(cron = "0 45 3 * * *")
  /* package */ void checkForOrphanedAccounts() {
    final long orphanCount = accounts.countAccountsWithNoAuthMethod();
    if (orphanCount > 0) {
      LOG.warn(
          "event=account_auth_method_integrity_violation orphanCount={} "
              + "reason=BR-ID-02_never_zero_auth_methods",
          orphanCount);
    }
  }
}
