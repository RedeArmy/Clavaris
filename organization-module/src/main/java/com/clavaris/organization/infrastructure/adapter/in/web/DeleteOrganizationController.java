package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.organization.application.usecase.deleteorganization.DeleteOrganizationCommand;
import com.clavaris.organization.application.usecase.deleteorganization.DeleteOrganizationUseCase;
import com.clavaris.organization.application.usecase.deleteorganization.OrganizationNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * BR-DATA-02/03's own organization-level equivalent: {@code POST
 * /api/v1/admin/organizations/{organizationId}:delete} — operator/platform-tier only, same
 * `:delete` custom-method naming precedent as {@code DeleteAccountController}. The single most
 * destructive operation this management API exposes — an entire consuming system's whole account
 * pool, not one identity.
 */
@RestController
class DeleteOrganizationController {

  private final DeleteOrganizationUseCase useCase;

  /* package */ DeleteOrganizationController(final DeleteOrganizationUseCase useCase) {
    this.useCase = useCase;
  }

  // Two exits (404 on an unknown organizationId, 204 on success) — same rationale as
  // DeleteAccountController's own identical suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @Operation(
      summary = "Hard-delete an Organization and its entire owned account pool (BR-DATA-02/03)")
  @ApiResponse(responseCode = "204", description = "Deleted — permanent, not reversible")
  @ApiResponse(responseCode = "404", description = "No Organization exists with the given id")
  @PostMapping("/api/v1/admin/organizations/{organizationId}:delete")
  /* package */ ResponseEntity<Void> delete(
      @PathVariable final UUID organizationId, final Authentication authentication) {
    try {
      useCase.handle(
          new DeleteOrganizationCommand(
              organizationId, AuditActor.platformClient(authentication.getName())));
    } catch (final OrganizationNotFoundException _) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.noContent().build();
  }
}
