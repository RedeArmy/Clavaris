package com.clavaris.webhook.application.usecase.listwebhookendpointsfororganizationpaged;

import com.clavaris.common.domain.model.KeysetPage;
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
  public KeysetPage<WebhookEndpoint> handle(
      final ListWebhookEndpointsForOrganizationPagedQuery query) {
    return endpoints.findKeysetPageByOrganizationId(query.organizationId(), query.pageRequest());
  }
}
