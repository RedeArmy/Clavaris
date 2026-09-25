package com.clavaris.webhook.application.usecase.listwebhookdeliveriesfororganizationpaged;

import com.clavaris.common.domain.model.KeysetPage;
import com.clavaris.webhook.application.usecase.deliverpendingwebhooks.WebhookDeliveryRepository;
import com.clavaris.webhook.domain.model.WebhookDelivery;

public class ListWebhookDeliveriesForOrganizationPagedService
    implements ListWebhookDeliveriesForOrganizationPagedUseCase {

  private final WebhookDeliveryRepository deliveries;

  public ListWebhookDeliveriesForOrganizationPagedService(
      final WebhookDeliveryRepository deliveries) {
    this.deliveries = deliveries;
  }

  @Override
  public KeysetPage<WebhookDelivery> handle(
      final ListWebhookDeliveriesForOrganizationPagedQuery query) {
    return deliveries.findKeysetPageByOrganizationId(query.organizationId(), query.pageRequest());
  }
}
