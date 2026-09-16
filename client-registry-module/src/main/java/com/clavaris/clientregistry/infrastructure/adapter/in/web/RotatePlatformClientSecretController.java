package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import com.clavaris.clientregistry.application.usecase.bootstrapplatformclient.PlatformClientNotFoundException;
import com.clavaris.clientregistry.application.usecase.rotateplatformclientsecret.RotatePlatformClientSecretCommand;
import com.clavaris.clientregistry.application.usecase.rotateplatformclientsecret.RotatePlatformClientSecretResult;
import com.clavaris.clientregistry.application.usecase.rotateplatformclientsecret.RotatePlatformClientSecretUseCase;
import com.clavaris.clientregistry.domain.model.ConcurrentClientModificationException;
import com.clavaris.common.domain.model.AuditActor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * TD-SEC-018: {@code POST /api/v1/admin/platform-clients/{clientId}/rotate-secret} — the real,
 * code-driven way to rotate the single highest-value credential in the system, replacing raw SQL
 * against production ({@code incident-response-platform-client-compromise.md} §3a).
 */
@RestController
class RotatePlatformClientSecretController {

  private final RotatePlatformClientSecretUseCase useCase;

  /* package */ RotatePlatformClientSecretController(
      final RotatePlatformClientSecretUseCase useCase) {
    this.useCase = useCase;
  }

  // Three exits (404, 409 on a concurrent revoke/rotate race, 200) — same rationale as
  // SetRateLimitPolicyController's own suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @Operation(summary = "Rotate a PlatformClient's secret (TD-SEC-018)")
  @ApiResponse(
      responseCode = "200",
      description = "Rotated — the new raw secret is shown here exactly once")
  @ApiResponse(
      responseCode = "404",
      description = "No PlatformClient exists with the given clientId")
  @ApiResponse(
      responseCode = "409",
      description = "This PlatformClient was modified concurrently by another request — retry")
  @PostMapping("/api/v1/admin/platform-clients/{clientId}/rotate-secret")
  /* package */ ResponseEntity<RotatePlatformClientSecretResponse> rotate(
      @PathVariable final String clientId, final Authentication authentication) {
    final RotatePlatformClientSecretResult result;
    try {
      result =
          useCase.handle(
              new RotatePlatformClientSecretCommand(
                  clientId, AuditActor.platformClient(authentication.getName())));
    } catch (final PlatformClientNotFoundException _) {
      return ResponseEntity.notFound().build();
    } catch (final ConcurrentClientModificationException _) {
      return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }
    return ResponseEntity.ok(RotatePlatformClientSecretResponse.from(result));
  }
}
