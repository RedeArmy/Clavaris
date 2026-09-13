package com.clavaris.app.infrastructure.config;

import com.clavaris.organization.application.usecase.deleteorganization.OrganizationWebhookDataEraser;
import com.clavaris.webhook.application.usecase.deliverpendingwebhooks.WebhookDeliveryRepository;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.WebhookEndpointRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Implements organization-module's outbound port — the bridge lives in {@code app}, not either
 * business module, same module-graph reason every other cross-module bridge in this package already
 * documents. TD-FUT-032/SDE-III review, 2026-09-13: real gap closed — {@code
 * DeleteOrganizationService} erased identity-module/client-registry-module data on delete from day
 * one, but never webhook-module's, even after that module shipped (2026-09-02).
 *
 * <p>Deliveries first, then endpoints — no DB-level FK enforces this order (webhook_deliveries
 * carries no FK to webhook_endpoints either, same cross-module-boundary posture the endpoints
 * table's own migration comment documents for {@code organizations}), but it's the logical
 * dependency direction and costs nothing to get right.
 */
@Component
class OrganizationWebhookDataEraserBridge implements OrganizationWebhookDataEraser {

  private final WebhookDeliveryRepository deliveries;
  private final WebhookEndpointRepository endpoints;

  /* package */ OrganizationWebhookDataEraserBridge(
      final WebhookDeliveryRepository deliveries, final WebhookEndpointRepository endpoints) {
    this.deliveries = deliveries;
    this.endpoints = endpoints;
  }

  @Override
  public void eraseAllFor(final UUID organizationId) {
    deliveries.deleteAllByOrganizationId(organizationId);
    endpoints.deleteAllByOrganizationId(organizationId);
  }
}
