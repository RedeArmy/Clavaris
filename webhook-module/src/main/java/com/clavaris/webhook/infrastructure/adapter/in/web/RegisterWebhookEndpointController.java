package com.clavaris.webhook.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.OrganizationNotFoundException;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.RegisterWebhookEndpointCommand;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.RegisterWebhookEndpointResult;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.RegisterWebhookEndpointUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADR-0007: {@code POST /api/v1/admin/organizations/{organizationId}/webhook-endpoints} — same
 * "operator only, a separate call from Organization/OAuthClient creation" shape as
 * client-registry-module's own {@code RegisterOAuthClientController}. Authentication/authorization
 * enforced by {@code app}'s own {@code AdminApiSecurityConfig}, not here.
 */
@RestController
class RegisterWebhookEndpointController {

  private final RegisterWebhookEndpointUseCase useCase;

  /* package */ RegisterWebhookEndpointController(final RegisterWebhookEndpointUseCase useCase) {
    this.useCase = useCase;
  }

  @SuppressWarnings("PMD.OnlyOneReturn")
  @Operation(summary = "Register a new WebhookEndpoint under an Organization (ADR-0007)")
  @ApiResponse(
      responseCode = "201",
      description = "WebhookEndpoint created — signingSecret is shown here exactly once")
  @ApiResponse(responseCode = "404", description = "No Organization exists with the given id")
  @PostMapping("/api/v1/admin/organizations/{organizationId}/webhook-endpoints")
  /* package */ ResponseEntity<RegisterWebhookEndpointResponse> register(
      @PathVariable final UUID organizationId,
      @Valid @RequestBody final RegisterWebhookEndpointRequest request,
      final Authentication authentication) {
    final RegisterWebhookEndpointResult result;
    try {
      result =
          useCase.handle(
              new RegisterWebhookEndpointCommand(
                  organizationId,
                  request.url(),
                  request.description(),
                  request.subscribedEventTypes(),
                  AuditActor.platformClient(authentication.getName())));
    } catch (final OrganizationNotFoundException _) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(RegisterWebhookEndpointResponse.from(result));
  }
}
