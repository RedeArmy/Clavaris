package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.organization.application.usecase.deleteorganizationsocialcredential.DeleteOrganizationSocialCredentialCommand;
import com.clavaris.organization.application.usecase.deleteorganizationsocialcredential.DeleteOrganizationSocialCredentialUseCase;
import com.clavaris.organization.domain.model.SocialProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADR-0022: {@code DELETE
 * /api/v1/admin/organizations/{organizationId}/social-credentials/{provider}} — reverts the
 * Organization to the shared Clavaris app for this provider. Idempotent, same rationale as {@code
 * DeleteOrganizationSocialCredentialService}'s own Javadoc — deleting a row that never existed is a
 * safe no-op, not an error, so there is no 404 case here.
 */
@RestController
class DeleteOrganizationSocialCredentialController {

  private final DeleteOrganizationSocialCredentialUseCase useCase;

  /* package */ DeleteOrganizationSocialCredentialController(
      final DeleteOrganizationSocialCredentialUseCase useCase) {
    this.useCase = useCase;
  }

  @Operation(summary = "Revert an Organization to the shared Clavaris OAuth app (ADR-0022)")
  @ApiResponse(responseCode = "204", description = "Reverted (or already using the shared app)")
  @DeleteMapping("/api/v1/admin/organizations/{organizationId}/social-credentials/{provider}")
  /* package */ ResponseEntity<Void> delete(
      @PathVariable final UUID organizationId,
      @PathVariable final SocialProvider provider,
      final Authentication authentication) {
    useCase.handle(
        new DeleteOrganizationSocialCredentialCommand(
            organizationId, provider, AuditActor.platformClient(authentication.getName())));
    return ResponseEntity.noContent().build();
  }
}
