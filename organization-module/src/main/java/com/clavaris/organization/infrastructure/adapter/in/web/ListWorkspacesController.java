package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.organization.application.usecase.listworkspacesfororganization.ListWorkspacesForOrganizationQuery;
import com.clavaris.organization.application.usecase.listworkspacesfororganization.ListWorkspacesForOrganizationUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/admin/organizations/{organizationId}/workspaces} — read-only, no dedicated
 * scope (only mutating admin-API actions get their own scope, same precedent as every other GET
 * endpoint on this surface). An unknown {@code organizationId} simply returns an empty list, same
 * "no distinct not-found state for a listing" convention as this codebase's other list endpoints.
 */
@RestController
class ListWorkspacesController {

  private final ListWorkspacesForOrganizationUseCase useCase;

  /* package */ ListWorkspacesController(final ListWorkspacesForOrganizationUseCase useCase) {
    this.useCase = useCase;
  }

  @Operation(summary = "List Workspaces within this Organization")
  @ApiResponse(responseCode = "200", description = "Possibly empty list")
  @GetMapping("/api/v1/admin/organizations/{organizationId}/workspaces")
  /* package */ List<WorkspaceResponse> list(@PathVariable final UUID organizationId) {
    return useCase.handle(new ListWorkspacesForOrganizationQuery(organizationId)).stream()
        .map(WorkspaceResponse::from)
        .toList();
  }
}
