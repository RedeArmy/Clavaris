package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.organization.application.usecase.changeworkspacememberrole.CannotDemoteLastAdminException;
import com.clavaris.organization.application.usecase.changeworkspacememberrole.ChangeWorkspaceMemberRoleCommand;
import com.clavaris.organization.application.usecase.changeworkspacememberrole.ChangeWorkspaceMemberRoleUseCase;
import com.clavaris.organization.application.usecase.changeworkspacememberrole.WorkspaceMembershipNotFoundException;
import com.clavaris.organization.domain.model.WorkspaceMembership;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code PUT /api/v1/admin/workspaces/{workspaceId}/members/{accountId}/role} — BR-WS-01's
 * replacement invariant (ADR-0010 §3 addendum): a demotion that would leave zero ADMINs is
 * rejected.
 */
@RestController
class ChangeWorkspaceMemberRoleController {

  private final ChangeWorkspaceMemberRoleUseCase useCase;

  /* package */ ChangeWorkspaceMemberRoleController(
      final ChangeWorkspaceMemberRoleUseCase useCase) {
    this.useCase = useCase;
  }

  // Three exits (404 unknown membership, 409 last-admin guard, 200 success) — same rationale as
  // AddWorkspaceMemberController's own identical suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @Operation(summary = "Change a Workspace member's role (BR-WS-05)")
  @ApiResponse(responseCode = "200", description = "Role changed")
  @ApiResponse(
      responseCode = "404",
      description = "No membership exists for this account/workspace")
  @ApiResponse(
      responseCode = "409",
      description = "This would leave the Workspace with zero ADMIN members")
  @PutMapping("/api/v1/admin/workspaces/{workspaceId}/members/{accountId}/role")
  /* package */ ResponseEntity<WorkspaceMembershipResponse> changeRole(
      @PathVariable final UUID workspaceId,
      @PathVariable final UUID accountId,
      @Valid @RequestBody final ChangeWorkspaceMemberRoleRequest request,
      final Authentication authentication) {
    final WorkspaceMembership membership;
    try {
      membership =
          useCase.handle(
              new ChangeWorkspaceMemberRoleCommand(
                  workspaceId,
                  accountId,
                  request.role(),
                  AuditActor.platformClient(authentication.getName())));
    } catch (final WorkspaceMembershipNotFoundException _) {
      return ResponseEntity.notFound().build();
    } catch (final CannotDemoteLastAdminException _) {
      return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }
    return ResponseEntity.ok(WorkspaceMembershipResponse.from(membership));
  }
}
