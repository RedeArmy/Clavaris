package com.clavaris.app.infrastructure.adapter.out.bridge;

import com.clavaris.identity.application.usecase.deferaccountsessionclosure.PendingSessionClosureRepository;
import com.clavaris.identity.domain.model.AccountId;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
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

  private final PendingClosureDrain<AccountId> drain;

  /* package */ PendingSessionClosureJob(
      final PendingSessionClosureRepository pending,
      final AccountSessionRevokerBridge sessions,
      final MeterRegistry meters,
      @Value("${clavaris.session.pending-closure-batch-size:100}") final int batchSize) {
    this.drain =
        new PendingClosureDrain<>(
            pending::oldest,
            sessions::revokeAllSessionsFor,
            pending::remove,
            pending::count,
            meters,
            "clavaris.session.closure.pending",
            "clavaris.session.closure.completed",
            "pending_session_closure",
            batchSize);
  }

  @Scheduled(
      initialDelayString = "${clavaris.session.pending-closure-initial-delay-ms:15000}",
      fixedDelayString = "${clavaris.session.pending-closure-interval-ms:15000}")
  public void closePendingSessions() {
    drain.run();
  }
}
