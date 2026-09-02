package com.clavaris.webhook.application.usecase.listwebhookdeliveriesforendpoint;

import com.clavaris.webhook.application.usecase.deliverpendingwebhooks.WebhookDeliveryRepository;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.WebhookEndpointNotFoundException;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.WebhookEndpointRepository;
import com.clavaris.webhook.domain.model.WebhookDelivery;
import java.util.List;

/**
 * Orchestration for {@link ListWebhookDeliveriesForEndpointUseCase}. Confirms the endpoint actually
 * exists first (a 404, not an empty list, for a typo'd id — same "fail loudly on a bad reference"
 * posture other lookups in this module already take).
 */
public class ListWebhookDeliveriesForEndpointService
    implements ListWebhookDeliveriesForEndpointUseCase {

  // BR-WEBHOOK: a bounded page, not the whole history — same "cheap to hold in memory, no need for
  // real pagination yet" posture other simple admin-API list endpoints in this codebase already
  // take (e.g. ListWorkspaceMembersService).
  private static final int MAX_RESULTS = 100;

  private final WebhookEndpointRepository endpoints;
  private final WebhookDeliveryRepository deliveries;

  public ListWebhookDeliveriesForEndpointService(
      final WebhookEndpointRepository endpoints, final WebhookDeliveryRepository deliveries) {
    this.endpoints = endpoints;
    this.deliveries = deliveries;
  }

  @Override
  public List<WebhookDelivery> handle(final ListWebhookDeliveriesForEndpointQuery query) {
    endpoints
        .findById(query.endpointId())
        .orElseThrow(() -> new WebhookEndpointNotFoundException(query.endpointId()));

    return deliveries.findAllByEndpointId(query.endpointId(), MAX_RESULTS);
  }
}
