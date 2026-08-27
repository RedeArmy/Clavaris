package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.organization.application.usecase.removeworkspacemember.CannotRemoveLastAdminException;
import com.clavaris.organization.application.usecase.removeworkspacemember.RemoveWorkspaceMemberCommand;
import com.clavaris.organization.application.usecase.removeworkspacemember.RemoveWorkspaceMemberUseCase;
import com.clavaris.organization.application.usecase.removeworkspacemember.WorkspaceMembershipNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * BR-WS-03: {@code POST /api/v1/admin/workspaces/{workspaceId}/members/{accountId}:remove} —
 * removes only the {@code WorkspaceMembership} row, never the {@code Account} itself (see {@code
 * RemoveWorkspaceMemberCommand}'s own Javadoc). Same {@code :remove} custom-method naming
 * convention as {@code DeleteAccountController}'s own {@code :delete}.
 */
@RestController
class RemoveWorkspaceMemberController {

  private final RemoveWorkspaceMemberUseCase useCase;

  /* package */ RemoveWorkspaceMemberController(final RemoveWorkspaceMemberUseCase useCase) {
    this.useCase = useCase;
  }

  // Three exits (404 unknown membership, 409 last-admin guard, 204 success) — same rationale as
  // ChangeWorkspaceMemberRoleController's own identical suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @Operation(summary = "Remove a member from a Workspace (BR-WS-03)")
  @ApiResponse(responseCode = "204", description = "Removed")
  @ApiResponse(
      responseCode = "404",
      description = "No membership exists for this account/workspace")
  @ApiResponse(
      responseCode = "409",
      description = "This would leave the Workspace with zero ADMIN members")
  @PostMapping("/api/v1/admin/workspaces/{workspaceId}/members/{accountId}:remove")
  /* package */ ResponseEntity<Void> remove(
      @PathVariable final UUID workspaceId,
      @PathVariable final UUID accountId,
      final Authentication authentication) {
    try {
      useCase.handle(
          new RemoveWorkspaceMemberCommand(
              workspaceId, accountId, AuditActor.platformClient(authentication.getName())));
    } catch (final WorkspaceMembershipNotFoundException _) {
      return ResponseEntity.notFound().build();
    } catch (final CannotRemoveLastAdminException _) {
      return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }
    return ResponseEntity.noContent().build();
  }
}
