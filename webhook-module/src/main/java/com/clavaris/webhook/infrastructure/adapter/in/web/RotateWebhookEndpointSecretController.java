package com.clavaris.webhook.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.WebhookEndpointNotFoundException;
import com.clavaris.webhook.application.usecase.rotatewebhookendpointsecret.RotateWebhookEndpointSecretCommand;
import com.clavaris.webhook.application.usecase.rotatewebhookendpointsecret.RotateWebhookEndpointSecretResult;
import com.clavaris.webhook.application.usecase.rotatewebhookendpointsecret.RotateWebhookEndpointSecretUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADR-0007's own first open question, resolved: {@code POST
 * /api/v1/admin/webhook-endpoints/{endpointId}:rotate-secret}.
 */
@RestController
class RotateWebhookEndpointSecretController {

  private final RotateWebhookEndpointSecretUseCase useCase;

  /* package */ RotateWebhookEndpointSecretController(
      final RotateWebhookEndpointSecretUseCase useCase) {
    this.useCase = useCase;
  }

  @SuppressWarnings("PMD.OnlyOneReturn")
  @Operation(summary = "Rotate a WebhookEndpoint's signing secret (ADR-0007)")
  @ApiResponse(
      responseCode = "200",
      description = "Rotated — signingSecret is shown here exactly once")
  @ApiResponse(responseCode = "404", description = "No WebhookEndpoint exists with this id")
  @PostMapping("/api/v1/admin/webhook-endpoints/{endpointId}:rotate-secret")
  /* package */ ResponseEntity<RotateWebhookEndpointSecretResponse> rotate(
      @PathVariable final UUID endpointId, final Authentication authentication) {
    final RotateWebhookEndpointSecretResult result;
    try {
      result =
          useCase.handle(
              new RotateWebhookEndpointSecretCommand(
                  endpointId, AuditActor.platformClient(authentication.getName())));
    } catch (final WebhookEndpointNotFoundException _) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok(RotateWebhookEndpointSecretResponse.from(result));
  }
}
