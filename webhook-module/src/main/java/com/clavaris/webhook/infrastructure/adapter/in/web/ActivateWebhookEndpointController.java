package com.clavaris.webhook.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.webhook.application.usecase.activatewebhookendpoint.ActivateWebhookEndpointCommand;
import com.clavaris.webhook.application.usecase.activatewebhookendpoint.ActivateWebhookEndpointUseCase;
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
 * {@code POST /api/v1/admin/webhook-endpoints/{endpointId}:activate} — reverses {@code
 * :deactivate}.
 */
@RestController
class ActivateWebhookEndpointController {

  private final ActivateWebhookEndpointUseCase useCase;

  /* package */ ActivateWebhookEndpointController(final ActivateWebhookEndpointUseCase useCase) {
    this.useCase = useCase;
  }

  @SuppressWarnings("PMD.OnlyOneReturn")
  @Operation(summary = "Reactivate a previously-deactivated WebhookEndpoint")
  @ApiResponse(responseCode = "200", description = "Activated")
  @ApiResponse(responseCode = "404", description = "No WebhookEndpoint exists with this id")
  @PostMapping("/api/v1/admin/webhook-endpoints/{endpointId}:activate")
  /* package */ ResponseEntity<WebhookEndpointResponse> activate(
      @PathVariable final UUID endpointId, final Authentication authentication) {
    final WebhookEndpoint activated;
    try {
      activated =
          useCase.handle(
              new ActivateWebhookEndpointCommand(
                  endpointId, AuditActor.platformClient(authentication.getName())));
    } catch (final WebhookEndpointNotFoundException _) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok(WebhookEndpointResponse.from(activated));
  }
}
