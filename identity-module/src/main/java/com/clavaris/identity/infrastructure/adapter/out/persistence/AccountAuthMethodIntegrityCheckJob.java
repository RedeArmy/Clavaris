package com.clavaris.identity.infrastructure.adapter.out.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * BR-ID-02 ("never zero auth methods") compensating control — a code review finding on ADR-0020
 * Phase 6 flagged that {@link JpaAccountRepository#save} had to stop unconditionally requiring a
 * {@code PasswordCredential} once social-only accounts became real: a brand-new social signup
 * legitimately saves an {@code Account} with no credential, then a {@code SocialIdentity} for it,
 * in that exact order (the FK on {@code social_identities.account_id} makes the reverse order
 * impossible). That ordering means a synchronous, mid-transaction guard at either repository's own
 * {@code save()} call can never see both rows at once, so it can never correctly distinguish "the
 * legitimate in-flight social signup" from "a future regression that never attaches either" — the
 * blanket throw removed in that fix cannot be reintroduced there without breaking the legitimate
 * case it exists to support.
 *
 * <p>With no synchronous guard possible, this is the compensating control: a daily sweep that
 * surfaces such a regression loudly within 24h via a log line, instead of it going unnoticed until
 * the affected {@code Account}'s owner discovers they can never actually authenticate. Deliberately
 * logs only the count, never which accounts (BR-DATA-01) — an operator who sees a non-zero count
 * investigates via a direct DB query; this job's own job is only "raise the alarm."
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
