package com.clavaris.webhook.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.webhook.application.usecase.replaywebhookdelivery.ReplayWebhookDeliveryCommand;
import com.clavaris.webhook.application.usecase.replaywebhookdelivery.ReplayWebhookDeliveryUseCase;
import com.clavaris.webhook.application.usecase.replaywebhookdelivery.WebhookDeliveryNotFoundException;
import com.clavaris.webhook.domain.model.WebhookDelivery;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * BR-WEBHOOK-03: {@code POST
 * /api/v1/admin/webhook-endpoints/{endpointId}/deliveries/{deliveryId}:replay}. {@code endpointId}
 * in the path names the resource being acted on for a consistent, discoverable URL shape — the
 * command itself only needs {@code deliveryId} ({@code WebhookDelivery} is already its own complete
 * primary key, same convention {@link ReplayWebhookDeliveryCommand}'s own Javadoc precedent
 * establishes elsewhere in this module).
 */
@RestController
class ReplayWebhookDeliveryController {

  private final ReplayWebhookDeliveryUseCase useCase;

  /* package */ ReplayWebhookDeliveryController(final ReplayWebhookDeliveryUseCase useCase) {
    this.useCase = useCase;
  }

  @SuppressWarnings("PMD.OnlyOneReturn")
  @Operation(summary = "Manually re-trigger one past WebhookDelivery (BR-WEBHOOK-03)")
  @ApiResponse(responseCode = "200", description = "Reset to PENDING, due immediately")
  @ApiResponse(responseCode = "404", description = "No WebhookDelivery exists with this id")
  @PostMapping("/api/v1/admin/webhook-endpoints/{endpointId}/deliveries/{deliveryId}:replay")
  /* package */ ResponseEntity<WebhookDeliveryResponse> replay(
      @PathVariable final UUID endpointId,
      @PathVariable final UUID deliveryId,
      final Authentication authentication) {
    final WebhookDelivery replayed;
    try {
      replayed =
          useCase.handle(
              new ReplayWebhookDeliveryCommand(
                  deliveryId, AuditActor.platformClient(authentication.getName())));
    } catch (final WebhookDeliveryNotFoundException _) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok(WebhookDeliveryResponse.from(replayed));
  }
}
