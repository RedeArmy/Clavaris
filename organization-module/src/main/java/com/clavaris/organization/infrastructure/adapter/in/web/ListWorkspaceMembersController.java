package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.organization.application.usecase.listworkspacemembers.ListWorkspaceMembersQuery;
import com.clavaris.organization.application.usecase.listworkspacemembers.ListWorkspaceMembersUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/admin/workspaces/{workspaceId}/members} — read-only, no dedicated scope, same
 * "only mutating actions get their own scope" precedent as {@code ListWorkspacesController}.
 */
@RestController
class ListWorkspaceMembersController {

  private final ListWorkspaceMembersUseCase useCase;

  /* package */ ListWorkspaceMembersController(final ListWorkspaceMembersUseCase useCase) {
    this.useCase = useCase;
  }

  @Operation(summary = "List members of a Workspace")
  @ApiResponse(responseCode = "200", description = "Possibly empty list")
  @GetMapping("/api/v1/admin/workspaces/{workspaceId}/members")
  /* package */ List<WorkspaceMembershipResponse> list(@PathVariable final UUID workspaceId) {
    return useCase.handle(new ListWorkspaceMembersQuery(workspaceId)).stream()
        .map(WorkspaceMembershipResponse::from)
        .toList();
  }
}
