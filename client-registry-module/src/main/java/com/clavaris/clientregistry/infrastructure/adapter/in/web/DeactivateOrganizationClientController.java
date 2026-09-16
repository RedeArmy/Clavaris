package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import com.clavaris.clientregistry.application.usecase.createorganizationclient.OrganizationClientNotFoundException;
import com.clavaris.clientregistry.application.usecase.deactivateorganizationclient.DeactivateOrganizationClientCommand;
import com.clavaris.clientregistry.application.usecase.deactivateorganizationclient.DeactivateOrganizationClientUseCase;
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

  // Three exits (404 on an unknown clientId, 409 on a concurrent rotate-secret/revoke race, 204 on
  // success) — same rationale as SetRateLimitPolicyController's own identical suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @Operation(summary = "Revoke an OrganizationClient / Secret Key (ADR-0023)")
  @ApiResponse(responseCode = "204", description = "Revoked — future token requests are rejected")
  @ApiResponse(
      responseCode = "404",
      description = "No OrganizationClient exists with the given clientId")
  @ApiResponse(
      responseCode = "409",
      description = "This Secret Key was modified concurrently by another request — retry")
  @PostMapping("/api/v1/admin/organization-clients/{clientId}/revoke")
  /* package */ ResponseEntity<Void> revoke(
      @PathVariable final String clientId, final Authentication authentication) {
    try {
      // organizationId deliberately null — this is the platform-tier, unscoped endpoint; a
      // PlatformClient caller is trusted across every Organization by design (BR-PLATFORM-02).
      // See DeactivateOrganizationClientCommand's own Javadoc.
      useCase.handle(
          new DeactivateOrganizationClientCommand(
              clientId, null, AuditActor.platformClient(authentication.getName())));
    } catch (final OrganizationClientNotFoundException _) {
      return ResponseEntity.notFound().build();
    } catch (final ConcurrentClientModificationException _) {
      // SDE-III review, 2026-09-15: OrganizationClient's own @Version-backed conflict — see
      // ConcurrentClientModificationException's own Javadoc for the lost-update race this closes.
      return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }
    return ResponseEntity.noContent().build();
  }
}
