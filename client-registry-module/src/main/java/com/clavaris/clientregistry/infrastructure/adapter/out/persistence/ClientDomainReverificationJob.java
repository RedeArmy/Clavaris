package com.clavaris.clientregistry.infrastructure.adapter.out.persistence;

import com.clavaris.clientregistry.application.usecase.requestclientdomainconfig.ClientDomainConfigRepository;
import com.clavaris.clientregistry.application.usecase.verifyclientdomainownership.DnsTxtRecordLookup;
import com.clavaris.clientregistry.domain.model.ClientDomainConfig;
import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.common.infrastructure.adapter.out.persistence.PostgresAdvisoryJobLock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * TD-SEC-058: ADR-0009 §2's DNS TXT ownership challenge only ever ran once, at operator-triggered
 * verification time ({@code VerifyClientDomainOwnershipService}) — a {@code VERIFIED} {@link
 * ClientDomainConfig} stayed trusted forever after, with no periodic re-check. A domain later
 * abandoned and re-acquired by a different party (a classic subdomain/domain takeover) would keep
 * its stale {@code VERIFIED} status indefinitely — and {@code isVerified()} is exactly what gates
 * the {@code Content-Security-Policy: frame-ancestors} relaxation {@code
 * EmbeddingEligibilityChecker} grants for iframe embedding, so a reclaimed domain would silently
 * stay browser-trusted as that client's own embedding origin.
 *
 * <p>Re-runs the identical DNS TXT lookup {@code VerifyClientDomainOwnershipService} itself
 * performs, but on a schedule rather than only on demand — every {@code VERIFIED} config whose own
 * {@code verifiedAt} is older than {@code reverificationDays} is re-checked; a still-matching
 * record refreshes {@code verifiedAt} (extends the trust window another {@code
 * reverificationDays}), a missing or mismatched one demotes the config to {@code FAILED} via the
 * same {@link ClientDomainConfig#markFailed()} the on-demand path already uses — {@code
 * isVerified()} then correctly stops granting the CSP relaxation until an operator re-verifies.
 * Same {@link PostgresAdvisoryJobLock}-guarded, daily-check-for-staleness shape {@code
 * WebhookDeliveryRetentionJob} already establishes (TD-FUT-033), not a literal "every N days" cron
 * — simpler, and idempotent if a tick is ever missed.
 *
 * <p>TD-PERF-044: a sweep is bounded, and holds no transaction while it waits on DNS. The old
 * version was one {@code @Transactional} over every stale domain, so an unreachable resolver (about
 * 21 s per lookup after the JNDI retries) kept a pooled connection for as long as the whole list
 * took. Now each run reads at most {@code batchSize} of the stalest domains, stops starting new
 * lookups once {@code budget} has elapsed (the rest are first in line next run, still being the
 * stalest), and opens a transaction only around each domain's save and audit write, so a failed
 * audit write cannot leave a status change unrecorded. The advisory lock itself is a session lock
 * on its own connection, held for the run by design.
 */
@Component
class ClientDomainReverificationJob {

  private static final Logger LOG = LoggerFactory.getLogger(ClientDomainReverificationJob.class);

  // Same challenge-record namespace VerifyClientDomainOwnershipService's own identical constant
  // already establishes — duplicated, not shared, since it's a single string literal used by only
  // these two classes.
  private static final String CHALLENGE_PREFIX = "_clavaris-challenge.";

  private final ClientDomainConfigRepository domainConfigs;
  private final DnsTxtRecordLookup dnsLookup;
  private final AuditEventRecorder auditEvents;
  private final int reverificationDays;
  private final int batchSize;
  private final Duration budget;
  private final PostgresAdvisoryJobLock jobLock;
  private final TransactionOperations transactions;
  private final Clock clock;

  // java:S107: the collaborating ports and config values of a PostgresAdvisoryJobLock-guarded job.
  // @Autowired: the second constructor exists for tests (a Clock), and with two Spring must be told
  // which.
  @Autowired
  @SuppressWarnings("java:S107")
  /* package */ ClientDomainReverificationJob(
      final ClientDomainConfigRepository domainConfigs,
      final DnsTxtRecordLookup dnsLookup,
      final AuditEventRecorder auditEvents,
      @Value("${clavaris.client-domain.reverification-days:60}") final int reverificationDays,
      @Value("${clavaris.client-domain.reverification-batch-size:100}") final int batchSize,
      @Value("${clavaris.client-domain.reverification-budget-seconds:600}")
          final long budgetSeconds,
      final PostgresAdvisoryJobLock jobLock,
      final TransactionOperations transactions) {
    this(
        domainConfigs,
        dnsLookup,
        auditEvents,
        reverificationDays,
        batchSize,
        Duration.ofSeconds(budgetSeconds),
        jobLock,
        transactions,
        Clock.systemUTC());
  }

  @SuppressWarnings("java:S107")
  /* package */ ClientDomainReverificationJob(
      final ClientDomainConfigRepository domainConfigs,
      final DnsTxtRecordLookup dnsLookup,
      final AuditEventRecorder auditEvents,
      final int reverificationDays,
      final int batchSize,
      final Duration budget,
      final PostgresAdvisoryJobLock jobLock,
      final TransactionOperations transactions,
      final Clock clock) {
    this.domainConfigs = domainConfigs;
    this.dnsLookup = dnsLookup;
    this.auditEvents = auditEvents;
    this.reverificationDays = reverificationDays;
    this.batchSize = batchSize;
    this.budget = budget;
    this.jobLock = jobLock;
    this.transactions = transactions;
    this.clock = clock;
  }

  // Daily, off-peak (04:30 server time) — after WebhookDeliveryRetentionJob's own 04:00 slot, same
  // "no other scheduled job to coordinate against yet" posture that job's own Javadoc documents.
  // TD-FUT-033: guarded by PostgresAdvisoryJobLock, same reasoning as every sibling job.
  // Deliberately not @Transactional (TD-PERF-044): see the class Javadoc.
  @Scheduled(cron = "0 30 4 * * *")
  /* package */ void reverifyStaleDomains() {
    jobLock.runIfLockAcquired(
        "client_domain_reverification", LOG, this::reverifyStaleDomainsLocked);
  }

  private void reverifyStaleDomainsLocked() {
    final Instant start = clock.instant();
    final Instant cutoff = start.minus(reverificationDays, ChronoUnit.DAYS);
    final List<ClientDomainConfig> stale = domainConfigs.findStalestVerified(cutoff, batchSize);
    int checked = 0;
    for (final ClientDomainConfig config : stale) {
      if (Duration.between(start, clock.instant()).compareTo(budget) >= 0) {
        LOG.warn(
            "event=client_domain_reverification_budget_exhausted checkedCount={} remainingCount={}",
            checked,
            stale.size() - checked);
        break;
      }
      reverifyOne(config);
      checked++;
    }
    if (checked > 0) {
      LOG.info("event=client_domain_reverification_swept checkedCount={}", checked);
    }
  }

  private void reverifyOne(final ClientDomainConfig config) {
    final String hostname = config.hostname().orElseThrow();
    final String expectedToken = config.dnsTxtChallengeToken().orElseThrow();
    final boolean matched =
        dnsLookup.lookupTxtRecords(CHALLENGE_PREFIX + hostname).stream()
            .anyMatch(expectedToken::equals);

    final ClientDomainConfig result = matched ? config.markVerified() : config.markFailed();
    transactions.executeWithoutResult(status -> persist(config, result, hostname, matched));

    if (!matched) {
      LOG.warn(
          "event=client_domain_reverification_failed oauthClientId={} hostname={}",
          config.oauthClientId(),
          hostname);
    }
  }

  private void persist(
      final ClientDomainConfig config,
      final ClientDomainConfig result,
      final String hostname,
      final boolean matched) {
    domainConfigs.save(result);
    // No operator/tenant actor exists for a scheduled, system-triggered action — same
    // AuditActor.platformClient(String) shape every other audited action uses, with a
    // self-describing pseudo-clientId identifying this job as the actor, not a real credential.
    auditEvents.write(
        AuditActor.platformClient("system:client-domain-reverification-job"),
        matched ? "client_domain_config.reverified" : "client_domain_config.reverification_failed",
        "OAuthClient",
        config.oauthClientId().toString(),
        "hostname=" + hostname);
  }
}
