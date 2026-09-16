package com.clavaris.webhook.application.usecase.getwebhookendpointfororganization;

import com.clavaris.webhook.application.usecase.registerwebhookendpoint.WebhookEndpointRepository;
import com.clavaris.webhook.domain.model.WebhookEndpoint;
import java.util.Optional;

public class GetWebhookEndpointForOrganizationService
    implements GetWebhookEndpointForOrganizationUseCase {

  private final WebhookEndpointRepository endpoints;

  public GetWebhookEndpointForOrganizationService(final WebhookEndpointRepository endpoints) {
    this.endpoints = endpoints;
  }

  @Override
  public Optional<WebhookEndpoint> handle(final GetWebhookEndpointForOrganizationQuery query) {
    return endpoints.findByIdAndOrganizationId(query.endpointId(), query.organizationId());
  }
}
