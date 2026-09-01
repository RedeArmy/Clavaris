package com.clavaris.identity.application.usecase.registeraccount;

import com.clavaris.identity.domain.model.AccountId;
import org.slf4j.Logger;

/**
 * TD-SEC-036's isolated-write pattern, extracted once two independent callers duplicated it (code
 * review, 2026-09-01) — see {@link EventOutboxWriter}'s own Javadoc for when this pattern applies
 * instead of the transactional, propagating one most callers still use.
 */
public final class BestEffortEventPublisher {

  private BestEffortEventPublisher() {}

  // IllegalStateException (JpaEventOutboxWriter's own serialization-bug signal) is logged louder
  // than an ordinary DataAccessException, but neither may ever propagate to the caller.
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  public static void publish(
      final Logger log,
      final EventOutboxWriter outbox,
      final String eventType,
      final AccountId aggregateId,
      final Object payload,
      final String failureLogEvent) {
    try {
      outbox.write(eventType, aggregateId, payload);
    } catch (final IllegalStateException e) {
      log.error(failureLogEvent, e);
    } catch (final RuntimeException e) {
      log.warn(failureLogEvent, e);
    }
  }
}
