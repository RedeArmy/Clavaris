package com.clavaris.clientregistry.infrastructure.adapter.out.persistence;

import com.clavaris.clientregistry.application.usecase.requestclientdomainconfig.ClientDomainConfigRepository;
import com.clavaris.clientregistry.application.usecase.verifyclientdomainownership.DnsTxtRecordLookup;
import com.clavaris.clientregistry.domain.model.ClientDomainConfig;
import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.common.infrastructure.adapter.out.persistence.PostgresAdvisoryJobLock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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
  private final PostgresAdvisoryJobLock jobLock;

  // java:S107: six collaborating ports/config values — same shape every other
  // PostgresAdvisoryJobLock-guarded job in this codebase already has.
  @SuppressWarnings("java:S107")
  /* package */ ClientDomainReverificationJob(
      final ClientDomainConfigRepository domainConfigs,
      final DnsTxtRecordLookup dnsLookup,
      final AuditEventRecorder auditEvents,
      @Value("${clavaris.client-domain.reverification-days:60}") final int reverificationDays,
      final PostgresAdvisoryJobLock jobLock) {
    this.domainConfigs = domainConfigs;
    this.dnsLookup = dnsLookup;
    this.auditEvents = auditEvents;
    this.reverificationDays = reverificationDays;
    this.jobLock = jobLock;
  }

  // Daily, off-peak (04:30 server time) — after WebhookDeliveryRetentionJob's own 04:00 slot, same
  // "no other scheduled job to coordinate against yet" posture that job's own Javadoc documents.
  // TD-FUT-033: guarded by PostgresAdvisoryJobLock, same reasoning as every sibling job.
  @Scheduled(cron = "0 30 4 * * *")
  @Transactional
  /* package */ void reverifyStaleDomains() {
    jobLock.runIfLockAcquired(
        "client_domain_reverification", LOG, this::reverifyStaleDomainsLocked);
  }

  private void reverifyStaleDomainsLocked() {
    final Instant cutoff = Instant.now().minus(reverificationDays, ChronoUnit.DAYS);
    final List<ClientDomainConfig> stale =
        domainConfigs.findAllVerified().stream()
            .filter(
                config ->
                    config.verifiedAt().map(verifiedAt -> verifiedAt.isBefore(cutoff)).orElse(true))
            .toList();
    for (final ClientDomainConfig config : stale) {
      reverifyOne(config);
    }
    if (!stale.isEmpty()) {
      LOG.info("event=client_domain_reverification_swept checkedCount={}", stale.size());
    }
  }

  private void reverifyOne(final ClientDomainConfig config) {
    final String hostname = config.hostname().orElseThrow();
    final String expectedToken = config.dnsTxtChallengeToken().orElseThrow();
    final boolean matched =
        dnsLookup.lookupTxtRecords(CHALLENGE_PREFIX + hostname).stream()
            .anyMatch(expectedToken::equals);

    final ClientDomainConfig result = matched ? config.markVerified() : config.markFailed();
    domainConfigs.save(result);

    if (!matched) {
      LOG.warn(
          "event=client_domain_reverification_failed oauthClientId={} hostname={}",
          config.oauthClientId(),
          hostname);
    }

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
