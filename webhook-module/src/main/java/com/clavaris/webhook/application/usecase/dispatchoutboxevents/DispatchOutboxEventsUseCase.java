package com.clavaris.webhook.application.usecase.dispatchoutboxevents;

/**
 * Phase A of the dispatcher (ADR-0007 §1) — fan out newly-observed outbox events into deliveries.
 */
@FunctionalInterface
public interface DispatchOutboxEventsUseCase {

  void dispatchPendingEvents();
}
