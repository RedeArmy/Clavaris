package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.organization.application.usecase.addworkspacemember.AccountProvisioner;
import com.clavaris.organization.application.usecase.addworkspacemember.AddWorkspaceMemberCommand;
import com.clavaris.organization.application.usecase.addworkspacemember.AddWorkspaceMemberUseCase;
import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceNotFoundException;
import com.clavaris.organization.domain.model.WorkspaceMembership;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * BR-WS-04: {@code POST /api/v1/admin/workspaces/{workspaceId}/members} — provisions a brand-new
 * {@code Account} and adds it to this Workspace in one call; there is no invitation step in v1 (see
 * {@code AddWorkspaceMemberService}'s own Javadoc).
 */
@RestController
class AddWorkspaceMemberController {

  private final AddWorkspaceMemberUseCase useCase;

  /* package */ AddWorkspaceMemberController(final AddWorkspaceMemberUseCase useCase) {
    this.useCase = useCase;
  }

  // Three exits (404 unknown workspace, 409 email already registered, 201 success) — same
  // rationale as every other admin-API controller's identical suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @Operation(summary = "Add a new member to a Workspace, provisioning a real Account (BR-WS-04)")
  @ApiResponse(responseCode = "201", description = "Account provisioned; membership created")
  @ApiResponse(responseCode = "404", description = "No Workspace exists with the given id")
  @ApiResponse(
      responseCode = "409",
      description = "The given email is already registered in this Workspace's own Organization")
  @PostMapping("/api/v1/admin/workspaces/{workspaceId}/members")
  /* package */ ResponseEntity<WorkspaceMembershipResponse> add(
      @PathVariable final UUID workspaceId,
      @Valid @RequestBody final AddWorkspaceMemberRequest request,
      final Authentication authentication) {
    final WorkspaceMembership membership;
    try {
      membership =
          useCase.handle(
              new AddWorkspaceMemberCommand(
                  workspaceId,
                  request.email(),
                  request.roleOrDefault(),
                  AuditActor.platformClient(authentication.getName())));
    } catch (final WorkspaceNotFoundException _) {
      return ResponseEntity.notFound().build();
    } catch (final AccountProvisioner.AccountAlreadyExistsException _) {
      return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(WorkspaceMembershipResponse.from(membership));
  }
}
