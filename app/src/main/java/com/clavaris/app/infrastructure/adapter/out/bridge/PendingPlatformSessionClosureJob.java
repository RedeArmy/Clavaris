package com.clavaris.app.infrastructure.adapter.out.bridge;

import com.clavaris.identity.application.usecase.deferplatformsessionclosure.PendingPlatformSessionClosureRepository;
import com.clavaris.identity.domain.model.PlatformAccountId;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * TD-ARCH-063: closes the dashboard sessions of the operator accounts {@link
 * DeferringPlatformAccountSessionRevoker} could not, as soon as the session store answers again.
 * Same shape as {@link PendingSessionClosureJob}: idempotent, safe on several instances at once,
 * stops at the first failure of the store and leaves the rest for the next run.
 */
@Component
public class PendingPlatformSessionClosureJob {

  private static final Logger LOG = LoggerFactory.getLogger(PendingPlatformSessionClosureJob.class);

  private final PendingPlatformSessionClosureRepository pending;
  private final PlatformAccountSessionRevokerBridge sessions;
  private final MeterRegistry meters;
  private final int batchSize;

  // TD-PROC-036: the backlog, refreshed after every run and read by the gauge, so a scrape never
  // queries the database. Held in a field: a gauge keeps only a weak reference.
  private final AtomicLong waiting = new AtomicLong();

  /* package */ PendingPlatformSessionClosureJob(
      final PendingPlatformSessionClosureRepository pending,
      final PlatformAccountSessionRevokerBridge sessions,
      final MeterRegistry meters,
      @Value("${clavaris.session.pending-closure-batch-size:100}") final int batchSize) {
    this.pending = pending;
    this.sessions = sessions;
    this.meters = meters;
    this.batchSize = batchSize;
    meters.gauge("clavaris.platform_session.closure.pending", waiting);
  }

  @Scheduled(
      initialDelayString = "${clavaris.session.pending-closure-initial-delay-ms:15000}",
      fixedDelayString = "${clavaris.session.pending-closure-interval-ms:15000}")
  public void closePendingSessions() {
    int closed = 0;
    for (final PlatformAccountId accountId : pending.oldest(batchSize)) {
      try {
        sessions.revokeAllSessionsFor(accountId);
      } catch (final DataAccessException failure) {
        LOG.debug(
            "event=pending_platform_session_closure_waiting reason={}",
            failure.getClass().getSimpleName());
        break;
      }
      pending.remove(accountId);
      closed++;
    }
    waiting.set(pending.count());
    if (closed > 0) {
      meters.counter("clavaris.platform_session.closure.completed").increment(closed);
      LOG.info("event=pending_platform_session_closure_completed count={}", closed);
    }
  }
}
