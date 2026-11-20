package com.clavaris.app.infrastructure.adapter.out.bridge;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;

/**
 * TD-ARCH-057 / TD-ARCH-063: the loop both closing jobs run, the tenant tier's and the operator
 * tier's. Closes the oldest waiting accounts, stops at the first failure of the session store and
 * leaves the rest for the next run, then refreshes the backlog gauge. One copy, so the two tiers
 * cannot drift apart in how they treat a Redis that answers only some of the time.
 *
 * @param <I> the id of the account whose sessions are closed
 */
/* package */ final class PendingClosureDrain<I> {

  private static final Logger LOG = LoggerFactory.getLogger(PendingClosureDrain.class);

  private final IntFunction<List<I>> oldest;
  private final Consumer<I> close;
  private final Consumer<I> remove;
  private final LongSupplier count;
  private final MeterRegistry meters;
  private final String completedCounter;
  private final String event;
  private final int batchSize;

  // TD-PROC-036: the backlog, refreshed after every run and read by the gauge, so a scrape never
  // queries the database. Held in a field: a gauge keeps only a weak reference.
  private final AtomicLong waiting = new AtomicLong();

  /**
   * @param event the prefix of the log events, such as {@code pending_session_closure}
   */
  /* package */ PendingClosureDrain(
      final IntFunction<List<I>> oldest,
      final Consumer<I> close,
      final Consumer<I> remove,
      final LongSupplier count,
      final MeterRegistry meters,
      final String gauge,
      final String completedCounter,
      final String event,
      final int batchSize) {
    this.oldest = oldest;
    this.close = close;
    this.remove = remove;
    this.count = count;
    this.meters = meters;
    this.completedCounter = completedCounter;
    this.event = event;
    this.batchSize = batchSize;
    meters.gauge(gauge, waiting);
  }

  /* package */ void run() {
    int closed = 0;
    for (final I accountId : oldest.apply(batchSize)) {
      try {
        close.accept(accountId);
      } catch (final DataAccessException failure) {
        LOG.debug("event={}_waiting reason={}", event, failure.getClass().getSimpleName());
        break;
      }
      remove.accept(accountId);
      closed++;
    }
    waiting.set(count.getAsLong());
    if (closed > 0) {
      meters.counter(completedCounter).increment(closed);
      LOG.info("event={}_completed count={}", event, closed);
    }
  }
}
