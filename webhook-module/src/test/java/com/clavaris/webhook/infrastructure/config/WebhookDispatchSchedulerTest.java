package com.clavaris.webhook.infrastructure.config;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.clavaris.webhook.application.usecase.deliverpendingwebhooks.DeliverPendingWebhooksUseCase;
import com.clavaris.webhook.application.usecase.dispatchoutboxevents.DispatchOutboxEventsUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * ADR-0007 §1/§2: proves each tick calls its own use case, and that a bad tick (a transient
 * exception from the use case) never propagates out of the scheduled method — same "isolated
 * best-effort write" posture {@code BestEffortEventPublisher}'s own tests already prove elsewhere
 * in this codebase.
 */
class WebhookDispatchSchedulerTest {

  private DispatchOutboxEventsUseCase dispatchOutboxEvents;
  private DeliverPendingWebhooksUseCase deliverPendingWebhooks;
  private WebhookDispatchScheduler scheduler;

  @BeforeEach
  void setUp() {
    dispatchOutboxEvents = mock(DispatchOutboxEventsUseCase.class);
    deliverPendingWebhooks = mock(DeliverPendingWebhooksUseCase.class);
    scheduler = new WebhookDispatchScheduler(dispatchOutboxEvents, deliverPendingWebhooks);
  }

  @Test
  void dispatchTickCallsTheFanOutUseCase() {
    scheduler.dispatchTick();

    verify(dispatchOutboxEvents).dispatchPendingEvents();
  }

  @Test
  void dispatchTickSwallowsARuntimeExceptionRatherThanKillingTheSchedulerThread() {
    doThrow(new RuntimeException("transient DB blip"))
        .when(dispatchOutboxEvents)
        .dispatchPendingEvents();

    scheduler.dispatchTick();

    verify(dispatchOutboxEvents, times(1)).dispatchPendingEvents();
  }

  @Test
  void deliveryTickCallsTheDeliveryUseCase() {
    scheduler.deliveryTick();

    verify(deliverPendingWebhooks).deliverDueDeliveries();
  }

  @Test
  void deliveryTickSwallowsARuntimeExceptionRatherThanKillingTheSchedulerThread() {
    doThrow(new RuntimeException("transient network blip"))
        .when(deliverPendingWebhooks)
        .deliverDueDeliveries();

    scheduler.deliveryTick();

    verify(deliverPendingWebhooks, times(1)).deliverDueDeliveries();
  }
}
