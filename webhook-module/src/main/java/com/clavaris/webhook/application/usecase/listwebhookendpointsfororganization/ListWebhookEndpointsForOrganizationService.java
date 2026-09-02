package com.clavaris.webhook.application.usecase.listwebhookendpointsfororganization;

import com.clavaris.webhook.application.usecase.registerwebhookendpoint.WebhookEndpointRepository;
import com.clavaris.webhook.domain.model.WebhookEndpoint;
import java.util.List;

public class ListWebhookEndpointsForOrganizationService
    implements ListWebhookEndpointsForOrganizationUseCase {

  private final WebhookEndpointRepository endpoints;

  public ListWebhookEndpointsForOrganizationService(final WebhookEndpointRepository endpoints) {
    this.endpoints = endpoints;
  }

  @Override
  public List<WebhookEndpoint> handle(final ListWebhookEndpointsForOrganizationQuery query) {
    return endpoints.findAllByOrganizationId(query.organizationId());
  }
}
