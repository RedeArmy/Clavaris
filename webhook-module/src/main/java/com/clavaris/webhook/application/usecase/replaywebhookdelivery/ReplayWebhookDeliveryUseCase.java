package com.clavaris.webhook.application.usecase.replaywebhookdelivery;

import com.clavaris.webhook.domain.model.WebhookDelivery;

@FunctionalInterface
public interface ReplayWebhookDeliveryUseCase {

  WebhookDelivery handle(ReplayWebhookDeliveryCommand command);
}
