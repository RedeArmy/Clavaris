package com.clavaris.app.infrastructure.adapter.out.bridge;

import com.clavaris.identity.application.usecase.confirmplatformaccountpasswordreset.PlatformAccountSessionRevoker;
import com.clavaris.identity.application.usecase.deferplatformsessionclosure.PendingPlatformSessionClosureRepository;
import com.clavaris.identity.domain.model.PlatformAccountId;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * TD-ARCH-063 / ADR-0045: the operator tier's counterpart of {@link
 * DeferringAccountSessionRevoker}. Suspending a PlatformAccount or resetting its password closes
 * its dashboard sessions through Redis, which used to fail the whole operation with a 500 while
 * Redis was down. The credentials and refresh tokens are revoked in Postgres in the same
 * transaction either way, so the account cannot sign in again; only a dashboard session that was
 * already open lingers, and it cannot be used while its store is down. So when the store cannot be
 * reached the account is recorded in {@code pending_platform_session_closures}, in the same
 * transaction, and {@link PendingPlatformSessionClosureJob} closes the sessions once Redis answers.
 * The pause after a failure is shared with the tenant tier ({@link SessionStoreCircuit}).
 */
// BR-ID-33: a Redis outage never stops an account from being suspended, banned or deleted.
@Component
@Primary
class DeferringPlatformAccountSessionRevoker implements PlatformAccountSessionRevoker {

  private static final Logger LOG =
      LoggerFactory.getLogger(DeferringPlatformAccountSessionRevoker.class);

  private final PlatformAccountSessionRevokerBridge sessions;
  private final PendingPlatformSessionClosureRepository pending;
  private final MeterRegistry meters;
  private final Clock clock;
  private final SessionStoreCircuit circuit;

  @Autowired
  /* package */ DeferringPlatformAccountSessionRevoker(
      final PlatformAccountSessionRevokerBridge sessions,
      final PendingPlatformSessionClosureRepository pending,
      final MeterRegistry meters,
      final SessionStoreCircuit circuit) {
    this(sessions, pending, meters, Clock.systemUTC(), circuit);
  }

  /* package */ DeferringPlatformAccountSessionRevoker(
      final PlatformAccountSessionRevokerBridge sessions,
      final PendingPlatformSessionClosureRepository pending,
      final MeterRegistry meters,
      final Clock clock,
      final SessionStoreCircuit circuit) {
    this.sessions = sessions;
    this.pending = pending;
    this.meters = meters;
    this.clock = clock;
    this.circuit = circuit;
  }

  @Override
  public void revokeAllSessionsFor(final PlatformAccountId platformAccountId) {
    if (circuit.isOpen()) {
      defer(platformAccountId);
      return;
    }
    try {
      sessions.revokeAllSessionsFor(platformAccountId);
    } catch (final DataAccessException failure) {
      // Never the account id or anything else of the account in the log (BR-DATA-01).
      LOG.warn(
          "event=platform_session_store_unreachable action=close_deferred reason={}",
          failure.getClass().getSimpleName());
      circuit.trip();
      defer(platformAccountId);
    }
  }

  private void defer(final PlatformAccountId platformAccountId) {
    pending.add(platformAccountId, clock.instant());
    meters.counter("clavaris.platform_session.closure.deferred").increment();
  }
}
