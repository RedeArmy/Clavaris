package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.organization.application.usecase.listorganizationsocialcredentials.ListOrganizationSocialCredentialsUseCase;
import com.clavaris.organization.application.usecase.listorganizationsocialcredentials.ListedOrganizationSocialCredential;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/admin/organizations/{organizationId}/social-credentials} — read-only, no
 * dedicated scope, same "only mutating actions get their own scope" precedent {@code
 * ListWorkspaceMembersController} already establishes. Never includes any secret material — see
 * {@link ListedOrganizationSocialCredential}'s own Javadoc.
 */
@RestController
class ListOrganizationSocialCredentialsController {

  private final ListOrganizationSocialCredentialsUseCase useCase;

  /* package */ ListOrganizationSocialCredentialsController(
      final ListOrganizationSocialCredentialsUseCase useCase) {
    this.useCase = useCase;
  }

  @Operation(summary = "List an Organization's own OAuth social credentials (ADR-0022)")
  @ApiResponse(responseCode = "200", description = "Possibly empty list, never includes secrets")
  @GetMapping("/api/v1/admin/organizations/{organizationId}/social-credentials")
  /* package */ List<ListedOrganizationSocialCredential> list(
      @PathVariable final UUID organizationId) {
    return useCase.handle(organizationId);
  }
}
