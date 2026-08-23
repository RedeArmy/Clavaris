package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import com.clavaris.clientregistry.application.usecase.bootstrapplatformclient.PlatformClientNotFoundException;
import com.clavaris.clientregistry.application.usecase.deactivateplatformclient.DeactivatePlatformClientCommand;
import com.clavaris.clientregistry.application.usecase.deactivateplatformclient.DeactivatePlatformClientUseCase;
import com.clavaris.common.domain.model.AuditActor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * TD-SEC-018: {@code POST /api/v1/admin/platform-clients/{clientId}/revoke} — the self-service
 * revocation path {@code incident-response-platform-client-compromise.md} §3a named as missing.
 */
@RestController
class DeactivatePlatformClientController {

  private final DeactivatePlatformClientUseCase useCase;

  /* package */ DeactivatePlatformClientController(final DeactivatePlatformClientUseCase useCase) {
    this.useCase = useCase;
  }

  // Two exits (404 on an unknown clientId, 204 on success) — same rationale as
  // SetRateLimitPolicyController's own suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @Operation(summary = "Revoke a PlatformClient (TD-SEC-018)")
  @ApiResponse(responseCode = "204", description = "Revoked — future token requests are rejected")
  @ApiResponse(
      responseCode = "404",
      description = "No PlatformClient exists with the given clientId")
  @PostMapping("/api/v1/admin/platform-clients/{clientId}/revoke")
  /* package */ ResponseEntity<Void> revoke(
      @PathVariable final String clientId, final Authentication authentication) {
    try {
      useCase.handle(
          new DeactivatePlatformClientCommand(
              clientId, AuditActor.platformClient(authentication.getName())));
    } catch (final PlatformClientNotFoundException _) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.noContent().build();
  }
}
