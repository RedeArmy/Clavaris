package com.clavaris.webhook.application.usecase.listwebhookendpointsfororganization;

import com.clavaris.webhook.domain.model.WebhookEndpoint;
import java.util.List;

@FunctionalInterface
public interface ListWebhookEndpointsForOrganizationUseCase {

  List<WebhookEndpoint> handle(ListWebhookEndpointsForOrganizationQuery query);
}
