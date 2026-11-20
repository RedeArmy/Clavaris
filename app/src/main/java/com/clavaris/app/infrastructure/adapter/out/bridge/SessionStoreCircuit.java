package com.clavaris.app.infrastructure.adapter.out.bridge;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * TD-ARCH-057 / TD-ARCH-063: one failed attempt to reach the session store pauses every further
 * attempt for a while, for the tenant and the operator tier alike. Deleting an Organization erases
 * every account and a command timeout per account would turn an outage into minutes; and Redis
 * being down is one fact, not two, so the first caller to find out tells the other.
 */
@Component
class SessionStoreCircuit {

  private final long pauseNanos;
  // Nanosecond tick before which the store is not tried; 0 means it is tried.
  private final AtomicLong skipUntilNanos = new AtomicLong();

  /* package */ SessionStoreCircuit(
      @Value("${clavaris.session.store-retry-pause-ms:30000}") final long pauseMillis) {
    this.pauseNanos = TimeUnit.MILLISECONDS.toNanos(pauseMillis);
  }

  /** Whether the store is being left alone for now. */
  /* package */ boolean isOpen() {
    return System.nanoTime() - skipUntilNanos.get() < 0;
  }

  /** The store just failed: leave it alone for the pause. */
  /* package */ void trip() {
    skipUntilNanos.set(System.nanoTime() + pauseNanos);
  }
}
