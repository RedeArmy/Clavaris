package com.clavaris.webhook.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.webhook.application.usecase.deactivatewebhookendpoint.DeactivateWebhookEndpointCommand;
import com.clavaris.webhook.application.usecase.deactivatewebhookendpoint.DeactivateWebhookEndpointUseCase;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.WebhookEndpointNotFoundException;
import com.clavaris.webhook.domain.model.WebhookEndpoint;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/admin/webhook-endpoints/{endpointId}:deactivate} — reversible, see {@code
 * DeactivateWebhookEndpointService}'s own Javadoc.
 */
@RestController
class DeactivateWebhookEndpointController {

  private final DeactivateWebhookEndpointUseCase useCase;

  /* package */ DeactivateWebhookEndpointController(
      final DeactivateWebhookEndpointUseCase useCase) {
    this.useCase = useCase;
  }

  @SuppressWarnings("PMD.OnlyOneReturn")
  @Operation(summary = "Deactivate a WebhookEndpoint (reversible)")
  @ApiResponse(responseCode = "200", description = "Deactivated")
  @ApiResponse(responseCode = "404", description = "No WebhookEndpoint exists with this id")
  @PostMapping("/api/v1/admin/webhook-endpoints/{endpointId}:deactivate")
  /* package */ ResponseEntity<WebhookEndpointResponse> deactivate(
      @PathVariable final UUID endpointId, final Authentication authentication) {
    final WebhookEndpoint deactivated;
    try {
      deactivated =
          useCase.handle(
              new DeactivateWebhookEndpointCommand(
                  endpointId, AuditActor.platformClient(authentication.getName())));
    } catch (final WebhookEndpointNotFoundException _) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok(WebhookEndpointResponse.from(deactivated));
  }
}
