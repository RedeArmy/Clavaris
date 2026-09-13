package com.clavaris.webhook.application.usecase.listwebhookendpointsfororganizationpaged;

import com.clavaris.common.domain.model.Page;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.WebhookEndpointRepository;
import com.clavaris.webhook.domain.model.WebhookEndpoint;

public class ListWebhookEndpointsForOrganizationPagedService
    implements ListWebhookEndpointsForOrganizationPagedUseCase {

  private final WebhookEndpointRepository endpoints;

  public ListWebhookEndpointsForOrganizationPagedService(
      final WebhookEndpointRepository endpoints) {
    this.endpoints = endpoints;
  }

  @Override
  public Page<WebhookEndpoint> handle(final ListWebhookEndpointsForOrganizationPagedQuery query) {
    return endpoints.findPageByOrganizationId(query.organizationId(), query.pageRequest());
  }
}
