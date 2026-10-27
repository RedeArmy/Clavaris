package com.clavaris.clientregistry.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.clavaris.clientregistry.application.usecase.requestclientdomainconfig.ClientDomainConfigRepository;
import com.clavaris.clientregistry.application.usecase.verifyclientdomainownership.DnsTxtRecordLookup;
import com.clavaris.clientregistry.domain.model.ClientDomainConfig;
import com.clavaris.clientregistry.domain.model.ClientDomainMode;
import com.clavaris.clientregistry.domain.model.DomainVerificationStatus;
import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.infrastructure.adapter.out.persistence.PostgresAdvisoryJobLock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionOperations;

/**
 * TD-SEC-058: proves the periodic re-verification job actually closes the stale-trust gap — a
 * {@code VERIFIED} config that no longer resolves (domain reclaimed/DNS record removed) demotes to
 * {@code FAILED}, and a fresh {@code VERIFIED} config within the reverification window is left
 * alone entirely.
 */
class ClientDomainReverificationJobTest {

  private static final int REVERIFICATION_DAYS = 60;
  private static final int BATCH_SIZE = 100;

  private ClientDomainConfigRepository domainConfigs;
  private DnsTxtRecordLookup dnsLookup;
  private AuditEventRecorder auditEvents;
  private ClientDomainReverificationJob job;
  private PostgresAdvisoryJobLock jobLock;

  @BeforeEach
  void setUp() {
    domainConfigs = mock(ClientDomainConfigRepository.class);
    dnsLookup = mock(DnsTxtRecordLookup.class);
    auditEvents = mock(AuditEventRecorder.class);
    jobLock = mock(PostgresAdvisoryJobLock.class);
    // The lock itself is a real Postgres advisory lock (own coverage: PostgresAdvisoryJobLockTest)
    // — this test is about reverifyStaleDomains's own logic, so the mock just always "acquires"
    // and runs the guarded Runnable synchronously, same "run the real body, skip the real lock"
    // shape this codebase's other job tests use when they don't need a real Postgres instance.
    doAnswer(
            invocation -> {
              invocation.getArgument(2, Runnable.class).run();
              return null;
            })
        .when(jobLock)
        .runIfLockAcquired(any(), any(), any());
    job = jobWith(BATCH_SIZE, Duration.ofMinutes(10), Clock.systemUTC());
  }

  private ClientDomainReverificationJob jobWith(
      final int batchSize, final Duration budget, final Clock clock) {
    return new ClientDomainReverificationJob(
        domainConfigs,
        dnsLookup,
        auditEvents,
        REVERIFICATION_DAYS,
        batchSize,
        budget,
        jobLock,
        TransactionOperations.withoutTransaction(),
        clock);
  }

  private ClientDomainConfig verifiedConfig(final Instant verifiedAt) {
    ClientDomainConfig pending =
        ClientDomainConfig.request(
            UUID.randomUUID(), ClientDomainMode.CNAME, "login.example.com", null);
    ClientDomainConfig verified = pending.markVerified();
    // markVerified() always stamps Instant.now() — reconstitute with the exact verifiedAt this
    // test needs to simulate staleness, same shape ClientDomainConfig.reconstitute already
    // supports for every other field.
    return ClientDomainConfig.reconstitute(
        verified.id(),
        verified.oauthClientId(),
        verified.mode().orElseThrow(),
        verified.hostname().orElseThrow(),
        DomainVerificationStatus.VERIFIED,
        verified.dnsTxtChallengeToken().orElseThrow(),
        verified.embeddingOrigin().orElse(null),
        verifiedAt,
        verified.createdAt(),
        verified.updatedAt());
  }

  @Test
  void demotesAStaleVerifiedConfigToFailedWhenTheDnsRecordNoLongerMatches() {
    ClientDomainConfig stale = verifiedConfig(Instant.now().minus(90, ChronoUnit.DAYS));
    when(domainConfigs.findStalestVerified(any(), anyInt())).thenReturn(List.of(stale));
    when(dnsLookup.lookupTxtRecords(any())).thenReturn(List.of());

    job.reverifyStaleDomains();

    ArgumentCaptor<ClientDomainConfig> saved = ArgumentCaptor.forClass(ClientDomainConfig.class);
    verify(domainConfigs).save(saved.capture());
    assertThat(saved.getValue().verificationStatus()).contains(DomainVerificationStatus.FAILED);
    assertThat(saved.getValue().isVerified()).isFalse();
  }

  @Test
  void refreshesVerifiedAtWhenAStaleConfigsDnsRecordStillMatches() {
    ClientDomainConfig stale = verifiedConfig(Instant.now().minus(90, ChronoUnit.DAYS));
    when(domainConfigs.findStalestVerified(any(), anyInt())).thenReturn(List.of(stale));
    when(dnsLookup.lookupTxtRecords("_clavaris-challenge.login.example.com"))
        .thenReturn(List.of(stale.dnsTxtChallengeToken().orElseThrow()));

    job.reverifyStaleDomains();

    ArgumentCaptor<ClientDomainConfig> saved = ArgumentCaptor.forClass(ClientDomainConfig.class);
    verify(domainConfigs).save(saved.capture());
    assertThat(saved.getValue().verificationStatus()).contains(DomainVerificationStatus.VERIFIED);
    assertThat(saved.getValue().verifiedAt().orElseThrow())
        .isAfter(stale.verifiedAt().orElseThrow());
  }

  @Test
  void asksTheRepositoryOnlyForTheStalestBatchBeyondTheWindowAndDoesNothingWhenThereIsNone() {
    when(domainConfigs.findStalestVerified(any(), anyInt())).thenReturn(List.of());

    job.reverifyStaleDomains();

    ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
    verify(domainConfigs).findStalestVerified(cutoff.capture(), eq(BATCH_SIZE));
    assertThat(cutoff.getValue())
        .isBefore(Instant.now().minus(REVERIFICATION_DAYS - 1, ChronoUnit.DAYS))
        .isAfter(Instant.now().minus(REVERIFICATION_DAYS + 1, ChronoUnit.DAYS));
    verify(domainConfigs, never()).save(any());
    verifyNoInteractions(dnsLookup);
    verifyNoInteractions(auditEvents);
  }

  // TD-PERF-044: an unreachable resolver costs about 21 s a lookup, so a sweep stops starting new
  // lookups once its budget is spent and leaves the rest, still the stalest, for the next run.
  @Test
  void stopsStartingLookupsOnceTheRunBudgetIsSpent() {
    ClientDomainConfig first = verifiedConfig(Instant.now().minus(90, ChronoUnit.DAYS));
    ClientDomainConfig second = verifiedConfig(Instant.now().minus(80, ChronoUnit.DAYS));
    when(domainConfigs.findStalestVerified(any(), anyInt())).thenReturn(List.of(first, second));
    when(dnsLookup.lookupTxtRecords(any())).thenReturn(List.of());
    Clock clock = mock(Clock.class);
    Instant start = Instant.parse("2026-10-26T04:30:00Z");
    // The sweep reads the clock to start, then before each domain: in budget, then over it.
    when(clock.instant()).thenReturn(start, start, start.plusSeconds(601));
    job = jobWith(BATCH_SIZE, Duration.ofSeconds(600), clock);

    job.reverifyStaleDomains();

    verify(dnsLookup, times(1)).lookupTxtRecords(any());
    verify(domainConfigs, times(1)).save(any());
  }

  @Test
  void savesAndAuditsInsideTheSameTransactionPerDomain() {
    ClientDomainConfig stale = verifiedConfig(Instant.now().minus(90, ChronoUnit.DAYS));
    when(domainConfigs.findStalestVerified(any(), anyInt())).thenReturn(List.of(stale));
    when(dnsLookup.lookupTxtRecords(any())).thenReturn(List.of());
    TransactionOperations transactions = mock(TransactionOperations.class);
    List<String> order = new ArrayList<>();
    doAnswer(
            invocation -> {
              order.add("begin");
              invocation.getArgument(0, Consumer.class).accept(null);
              order.add("commit");
              return null;
            })
        .when(transactions)
        .executeWithoutResult(any());
    doAnswer(
            invocation -> {
              order.add("save");
              return null;
            })
        .when(domainConfigs)
        .save(any());
    doAnswer(
            invocation -> {
              order.add("audit");
              return null;
            })
        .when(auditEvents)
        .write(any(), any(), any(), any(), any());
    job =
        new ClientDomainReverificationJob(
            domainConfigs,
            dnsLookup,
            auditEvents,
            REVERIFICATION_DAYS,
            BATCH_SIZE,
            Duration.ofMinutes(10),
            jobLock,
            transactions,
            Clock.systemUTC());

    job.reverifyStaleDomains();

    // DNS first, outside any transaction; then one transaction around save and audit together.
    assertThat(order).containsExactly("begin", "save", "audit", "commit");
  }

  @Test
  void recordsAReverificationFailedAuditEventOnDemotion() {
    ClientDomainConfig stale = verifiedConfig(Instant.now().minus(90, ChronoUnit.DAYS));
    when(domainConfigs.findStalestVerified(any(), anyInt())).thenReturn(List.of(stale));
    when(dnsLookup.lookupTxtRecords(any())).thenReturn(List.of());

    job.reverifyStaleDomains();

    verify(auditEvents)
        .write(
            any(),
            eq("client_domain_config.reverification_failed"),
            eq("OAuthClient"),
            eq(stale.oauthClientId().toString()),
            any());
  }
}
