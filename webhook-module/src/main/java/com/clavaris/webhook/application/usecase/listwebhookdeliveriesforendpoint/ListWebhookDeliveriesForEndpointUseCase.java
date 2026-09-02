package com.clavaris.webhook.application.usecase.listwebhookdeliveriesforendpoint;

import com.clavaris.webhook.domain.model.WebhookDelivery;
import java.util.List;

@FunctionalInterface
public interface ListWebhookDeliveriesForEndpointUseCase {

  List<WebhookDelivery> handle(ListWebhookDeliveriesForEndpointQuery query);
}
