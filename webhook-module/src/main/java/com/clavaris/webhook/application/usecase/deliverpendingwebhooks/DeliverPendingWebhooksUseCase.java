package com.clavaris.webhook.application.usecase.deliverpendingwebhooks;

/** Phase B of the dispatcher (ADR-0007 §2) — attempt every delivery currently due. */
@FunctionalInterface
public interface DeliverPendingWebhooksUseCase {

  void deliverDueDeliveries();
}
