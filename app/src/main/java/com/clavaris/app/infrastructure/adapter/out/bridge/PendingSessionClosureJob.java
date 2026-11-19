package com.clavaris.app.infrastructure.adapter.out.bridge;

import com.clavaris.identity.application.usecase.deferaccountsessionclosure.PendingSessionClosureRepository;
import com.clavaris.identity.domain.model.AccountId;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * TD-ARCH-057: closes the browser sessions of the accounts {@link DeferringAccountSessionRevoker}
 * could not, as soon as the session store answers again. Short interval on purpose: while Redis is
 * down no session can be used, and from the moment it returns the window in which a suspended
 * account's old session could still be presented is this interval.
 *
 * <p>Idempotent and safe on several instances at once: closing a session twice is a no-op and
 * removing a row twice too, so no lock is needed. It stops at the first failure of the store and
 * leaves the rest for the next run.
 */
@Component
public class PendingSessionClosureJob {

  private static final Logger LOG = LoggerFactory.getLogger(PendingSessionClosureJob.class);

  private final PendingSessionClosureRepository pending;
  private final AccountSessionRevokerBridge sessions;
  private final MeterRegistry meters;
  private final int batchSize;

  // TD-PROC-036: the backlog, refreshed after every run (every 15 s) and read by the gauge, so a
  // scrape never queries the database. Held in a field: a gauge keeps only a weak reference.
  private final AtomicLong waiting = new AtomicLong();

  /* package */ PendingSessionClosureJob(
      final PendingSessionClosureRepository pending,
      final AccountSessionRevokerBridge sessions,
      final MeterRegistry meters,
      @Value("${clavaris.session.pending-closure-batch-size:100}") final int batchSize) {
    this.pending = pending;
    this.sessions = sessions;
    this.meters = meters;
    this.batchSize = batchSize;
    meters.gauge("clavaris.session.closure.pending", waiting);
  }

  @Scheduled(
      initialDelayString = "${clavaris.session.pending-closure-initial-delay-ms:15000}",
      fixedDelayString = "${clavaris.session.pending-closure-interval-ms:15000}")
  public void closePendingSessions() {
    int closed = 0;
    for (final AccountId accountId : pending.oldest(batchSize)) {
      try {
        sessions.revokeAllSessionsFor(accountId);
      } catch (final DataAccessException failure) {
        LOG.debug(
            "event=pending_session_closure_waiting reason={}", failure.getClass().getSimpleName());
        break;
      }
      pending.remove(accountId);
      closed++;
    }
    waiting.set(pending.count());
    if (closed > 0) {
      meters.counter("clavaris.session.closure.completed").increment(closed);
      LOG.info("event=pending_session_closure_completed count={}", closed);
    }
  }
}
