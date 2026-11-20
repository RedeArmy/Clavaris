package com.clavaris.app.infrastructure.adapter.out.bridge;

import com.clavaris.identity.application.usecase.deferplatformsessionclosure.PendingPlatformSessionClosureRepository;
import com.clavaris.identity.domain.model.PlatformAccountId;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
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

  private final PendingClosureDrain<PlatformAccountId> drain;

  /* package */ PendingPlatformSessionClosureJob(
      final PendingPlatformSessionClosureRepository pending,
      final PlatformAccountSessionRevokerBridge sessions,
      final MeterRegistry meters,
      @Value("${clavaris.session.pending-closure-batch-size:100}") final int batchSize) {
    this.drain =
        new PendingClosureDrain<>(
            pending::oldest,
            sessions::revokeAllSessionsFor,
            pending::remove,
            pending::count,
            meters,
            "clavaris.platform_session.closure.pending",
            "clavaris.platform_session.closure.completed",
            "pending_platform_session_closure",
            batchSize);
  }

  @Scheduled(
      initialDelayString = "${clavaris.session.pending-closure-initial-delay-ms:15000}",
      fixedDelayString = "${clavaris.session.pending-closure-interval-ms:15000}")
  public void closePendingSessions() {
    drain.run();
  }
}
