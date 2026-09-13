package com.clavaris.app.infrastructure.config;

import com.clavaris.organization.application.usecase.getauditlogfororganization.WebhookEndpointIdsForAuditLogProvider;
import com.clavaris.webhook.application.usecase.listwebhookendpointsfororganization.ListWebhookEndpointsForOrganizationQuery;
import com.clavaris.webhook.application.usecase.listwebhookendpointsfororganization.ListWebhookEndpointsForOrganizationUseCase;
import com.clavaris.webhook.domain.model.WebhookEndpoint;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Implements organization-module's outbound port — the bridge lives in {@code app}, not either
 * business module, same module-graph reason every other cross-module bridge in this package already
 * documents.
 */
@SuppressWarnings("PMD.LongVariable")
@Component
class WebhookEndpointIdsForAuditLogProviderBridge implements WebhookEndpointIdsForAuditLogProvider {

  private final ListWebhookEndpointsForOrganizationUseCase listWebhookEndpoints;

  /* package */ WebhookEndpointIdsForAuditLogProviderBridge(
      final ListWebhookEndpointsForOrganizationUseCase listWebhookEndpoints) {
    this.listWebhookEndpoints = listWebhookEndpoints;
  }

  @Override
  public List<String> webhookEndpointIds(final UUID organizationId) {
    return listWebhookEndpoints
        .handle(new ListWebhookEndpointsForOrganizationQuery(organizationId))
        .stream()
        .map(WebhookEndpoint::id)
        .map(UUID::toString)
        .toList();
  }
}
