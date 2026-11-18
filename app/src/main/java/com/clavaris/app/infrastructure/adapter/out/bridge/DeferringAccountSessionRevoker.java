package com.clavaris.app.infrastructure.adapter.out.bridge;

import com.clavaris.identity.application.usecase.deferaccountsessionclosure.PendingSessionClosureRepository;
import com.clavaris.identity.application.usecase.rotaterefreshtoken.AccountSessionRevoker;
import com.clavaris.identity.domain.model.AccountId;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * TD-ARCH-057: closing an account's browser sessions reads the session index in Redis, and a
 * suspension or a deletion used to fail with a 500 after a command timeout whenever Redis was down.
 * The state change is what matters and it lives in Postgres: the credentials and the refresh tokens
 * are revoked there, so the account cannot sign in or refresh again whatever Redis says. Only a
 * browser session that was already open lingers, and it cannot be used while its store is down. So
 * when the store cannot be reached the account is recorded in {@code pending_session_closures}, in
 * the same transaction as the change, and {@link PendingSessionClosureJob} closes the sessions once
 * Redis answers (ADR-0045).
 *
 * <p>After one failure the store is not tried again for a while: a bulk operation (deleting an
 * Organization erases every account) would otherwise wait a command timeout per account.
 *
 * <p>Marked primary so every use case that asks for an {@link AccountSessionRevoker} (suspension,
 * ban, deletion, password reset, workspace removal) gets this one; the job asks for the bridge
 * itself.
 */
// BR-ID-33: a Redis outage never stops an account from being suspended, banned or deleted.
@Component
@Primary
class DeferringAccountSessionRevoker implements AccountSessionRevoker {

  private static final Logger LOG = LoggerFactory.getLogger(DeferringAccountSessionRevoker.class);

  private final AccountSessionRevokerBridge sessions;
  private final PendingSessionClosureRepository pending;
  private final MeterRegistry meters;
  private final Clock clock;
  private final long skipNanos;
  // Nanosecond tick before which the session store is not tried; 0 means it is tried.
  private final AtomicLong skipUntilNanos = new AtomicLong();

  @Autowired
  /* package */ DeferringAccountSessionRevoker(
      final AccountSessionRevokerBridge sessions,
      final PendingSessionClosureRepository pending,
      final MeterRegistry meters,
      @Value("${clavaris.session.store-retry-pause-ms:30000}") final long pauseMillis) {
    this(sessions, pending, meters, Clock.systemUTC(), pauseMillis);
  }

  /* package */ DeferringAccountSessionRevoker(
      final AccountSessionRevokerBridge sessions,
      final PendingSessionClosureRepository pending,
      final MeterRegistry meters,
      final Clock clock,
      final long pauseMillis) {
    this.sessions = sessions;
    this.pending = pending;
    this.meters = meters;
    this.clock = clock;
    this.skipNanos = TimeUnit.MILLISECONDS.toNanos(pauseMillis);
  }

  @Override
  public void revokeAllSessionsFor(final AccountId accountId) {
    if (System.nanoTime() - skipUntilNanos.get() < 0) {
      defer(accountId);
      return;
    }
    try {
      sessions.revokeAllSessionsFor(accountId);
    } catch (final DataAccessException failure) {
      // Never the account id or anything else of the account in the log (BR-DATA-01).
      LOG.warn(
          "event=session_store_unreachable action=close_deferred reason={}",
          failure.getClass().getSimpleName());
      skipUntilNanos.set(System.nanoTime() + skipNanos);
      defer(accountId);
    }
  }

  private void defer(final AccountId accountId) {
    pending.record(accountId, clock.instant());
    meters.counter("clavaris.session.closure.deferred").increment();
  }
}
