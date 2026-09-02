package com.clavaris.webhook.infrastructure.adapter.in.web;

import com.clavaris.webhook.application.usecase.listwebhookendpointsfororganization.ListWebhookEndpointsForOrganizationQuery;
import com.clavaris.webhook.application.usecase.listwebhookendpointsfororganization.ListWebhookEndpointsForOrganizationUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/admin/organizations/{organizationId}/webhook-endpoints} — read-only, no
 * dedicated scope, same "only mutating admin-API actions get their own scope" precedent as {@code
 * ListWorkspacesController}. Never returns a signing secret — see {@link WebhookEndpointResponse}'s
 * own Javadoc.
 */
@RestController
class ListWebhookEndpointsController {

  private final ListWebhookEndpointsForOrganizationUseCase useCase;

  /* package */ ListWebhookEndpointsController(
      final ListWebhookEndpointsForOrganizationUseCase useCase) {
    this.useCase = useCase;
  }

  @Operation(summary = "List WebhookEndpoints registered under this Organization")
  @ApiResponse(responseCode = "200", description = "Possibly empty list")
  @GetMapping("/api/v1/admin/organizations/{organizationId}/webhook-endpoints")
  /* package */ List<WebhookEndpointResponse> list(@PathVariable final UUID organizationId) {
    return useCase.handle(new ListWebhookEndpointsForOrganizationQuery(organizationId)).stream()
        .map(WebhookEndpointResponse::from)
        .toList();
  }
}
