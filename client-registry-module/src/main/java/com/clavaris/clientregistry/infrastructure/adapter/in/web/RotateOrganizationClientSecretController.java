package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import com.clavaris.clientregistry.application.usecase.createorganizationclient.OrganizationClientNotFoundException;
import com.clavaris.clientregistry.application.usecase.rotateorganizationclientsecret.RotateOrganizationClientSecretCommand;
import com.clavaris.clientregistry.application.usecase.rotateorganizationclientsecret.RotateOrganizationClientSecretResult;
import com.clavaris.clientregistry.application.usecase.rotateorganizationclientsecret.RotateOrganizationClientSecretUseCase;
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
 * ADR-0023: {@code POST /api/v1/admin/organization-clients/{clientId}/rotate-secret} — same
 * rationale as {@code RotatePlatformClientSecretController}, applied to a Secret Key instead of the
 * platform-wide credential.
 */
@RestController
class RotateOrganizationClientSecretController {

  private final RotateOrganizationClientSecretUseCase useCase;

  /* package */ RotateOrganizationClientSecretController(
      final RotateOrganizationClientSecretUseCase useCase) {
    this.useCase = useCase;
  }

  // Three exits (404, 409 on a concurrent revoke/rotate race, 200) — same rationale as
  // SetRateLimitPolicyController's own identical suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @Operation(summary = "Rotate an OrganizationClient's secret (ADR-0023)")
  @ApiResponse(
      responseCode = "200",
      description = "Rotated — the new raw secret is shown here exactly once")
  @ApiResponse(
      responseCode = "404",
      description = "No OrganizationClient exists with the given clientId")
  @ApiResponse(
      responseCode = "409",
      description = "This Secret Key was modified concurrently by another request — retry")
  @PostMapping("/api/v1/admin/organization-clients/{clientId}/rotate-secret")
  /* package */ ResponseEntity<RotateOrganizationClientSecretResponse> rotate(
      @PathVariable final String clientId, final Authentication authentication) {
    final RotateOrganizationClientSecretResult result;
    try {
      // organizationId deliberately null — same rationale as
      // DeactivateOrganizationClientController's own identical call.
      result =
          useCase.handle(
              new RotateOrganizationClientSecretCommand(
                  clientId, null, AuditActor.platformClient(authentication.getName())));
    } catch (final OrganizationClientNotFoundException _) {
      return ResponseEntity.notFound().build();
    } catch (final ConcurrentClientModificationException _) {
      return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }
    return ResponseEntity.ok(RotateOrganizationClientSecretResponse.from(result));
  }
}
