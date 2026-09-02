package com.clavaris.webhook.infrastructure.adapter.in.web;

import com.clavaris.webhook.application.usecase.listwebhookdeliveriesforendpoint.ListWebhookDeliveriesForEndpointQuery;
import com.clavaris.webhook.application.usecase.listwebhookdeliveriesforendpoint.ListWebhookDeliveriesForEndpointUseCase;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.WebhookEndpointNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/admin/webhook-endpoints/{endpointId}/deliveries} — the delivery
 * history/debugging view.
 */
@RestController
class ListWebhookDeliveriesController {

  private final ListWebhookDeliveriesForEndpointUseCase useCase;

  /* package */ ListWebhookDeliveriesController(
      final ListWebhookDeliveriesForEndpointUseCase useCase) {
    this.useCase = useCase;
  }

  @SuppressWarnings("PMD.OnlyOneReturn")
  @Operation(summary = "List recent deliveries for one WebhookEndpoint")
  @ApiResponse(responseCode = "200", description = "Possibly empty list, newest first")
  @ApiResponse(responseCode = "404", description = "No WebhookEndpoint exists with this id")
  @GetMapping("/api/v1/admin/webhook-endpoints/{endpointId}/deliveries")
  /* package */ ResponseEntity<List<WebhookDeliveryResponse>> list(
      @PathVariable final UUID endpointId) {
    final List<WebhookDeliveryResponse> deliveries;
    try {
      deliveries =
          useCase.handle(new ListWebhookDeliveriesForEndpointQuery(endpointId)).stream()
              .map(WebhookDeliveryResponse::from)
              .toList();
    } catch (final WebhookEndpointNotFoundException _) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok(deliveries);
  }
}
