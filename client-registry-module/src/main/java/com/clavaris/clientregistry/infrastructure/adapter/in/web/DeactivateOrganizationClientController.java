package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import com.clavaris.clientregistry.application.usecase.createorganizationclient.OrganizationClientNotFoundException;
import com.clavaris.clientregistry.application.usecase.deactivateorganizationclient.DeactivateOrganizationClientCommand;
import com.clavaris.clientregistry.application.usecase.deactivateorganizationclient.DeactivateOrganizationClientUseCase;
import com.clavaris.common.domain.model.AuditActor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADR-0023: {@code POST /api/v1/admin/organization-clients/{clientId}/revoke} — same rationale as
 * {@code DeactivatePlatformClientController}, applied to a Secret Key.
 */
@RestController
class DeactivateOrganizationClientController {

  private final DeactivateOrganizationClientUseCase useCase;

  /* package */ DeactivateOrganizationClientController(
      final DeactivateOrganizationClientUseCase useCase) {
    this.useCase = useCase;
  }

  @SuppressWarnings("PMD.OnlyOneReturn")
  @Operation(summary = "Revoke an OrganizationClient / Secret Key (ADR-0023)")
  @ApiResponse(responseCode = "204", description = "Revoked — future token requests are rejected")
  @ApiResponse(
      responseCode = "404",
      description = "No OrganizationClient exists with the given clientId")
  @PostMapping("/api/v1/admin/organization-clients/{clientId}/revoke")
  /* package */ ResponseEntity<Void> revoke(
      @PathVariable final String clientId, final Authentication authentication) {
    try {
      useCase.handle(
          new DeactivateOrganizationClientCommand(
              clientId, AuditActor.platformClient(authentication.getName())));
    } catch (final OrganizationClientNotFoundException _) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.noContent().build();
  }
}
