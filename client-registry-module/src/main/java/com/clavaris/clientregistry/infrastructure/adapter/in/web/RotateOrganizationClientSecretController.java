package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import com.clavaris.clientregistry.application.usecase.createorganizationclient.OrganizationClientNotFoundException;
import com.clavaris.clientregistry.application.usecase.rotateorganizationclientsecret.RotateOrganizationClientSecretCommand;
import com.clavaris.clientregistry.application.usecase.rotateorganizationclientsecret.RotateOrganizationClientSecretResult;
import com.clavaris.clientregistry.application.usecase.rotateorganizationclientsecret.RotateOrganizationClientSecretUseCase;
import com.clavaris.common.domain.model.AuditActor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
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

  @SuppressWarnings("PMD.OnlyOneReturn")
  @Operation(summary = "Rotate an OrganizationClient's secret (ADR-0023)")
  @ApiResponse(
      responseCode = "200",
      description = "Rotated — the new raw secret is shown here exactly once")
  @ApiResponse(
      responseCode = "404",
      description = "No OrganizationClient exists with the given clientId")
  @PostMapping("/api/v1/admin/organization-clients/{clientId}/rotate-secret")
  /* package */ ResponseEntity<RotateOrganizationClientSecretResponse> rotate(
      @PathVariable final String clientId, final Authentication authentication) {
    final RotateOrganizationClientSecretResult result;
    try {
      result =
          useCase.handle(
              new RotateOrganizationClientSecretCommand(
                  clientId, AuditActor.platformClient(authentication.getName())));
    } catch (final OrganizationClientNotFoundException _) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok(RotateOrganizationClientSecretResponse.from(result));
  }
}
