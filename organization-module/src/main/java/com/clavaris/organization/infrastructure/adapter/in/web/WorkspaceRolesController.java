package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceRoleNotFoundException;
import com.clavaris.organization.application.usecase.createworkspace.OrganizationNotFoundException;
import com.clavaris.organization.application.usecase.createworkspacerole.CreateWorkspaceRoleCommand;
import com.clavaris.organization.application.usecase.createworkspacerole.CreateWorkspaceRoleUseCase;
import com.clavaris.organization.application.usecase.createworkspacerole.DuplicateWorkspaceRoleNameException;
import com.clavaris.organization.application.usecase.deleteworkspacerole.CannotDeleteReservedWorkspaceRoleException;
import com.clavaris.organization.application.usecase.deleteworkspacerole.DeleteWorkspaceRoleCommand;
import com.clavaris.organization.application.usecase.deleteworkspacerole.DeleteWorkspaceRoleUseCase;
import com.clavaris.organization.application.usecase.deleteworkspacerole.WorkspaceRoleStillAssignedException;
import com.clavaris.organization.application.usecase.listworkspacerolesfororganization.ListWorkspaceRolesForOrganizationQuery;
import com.clavaris.organization.application.usecase.listworkspacerolesfororganization.ListWorkspaceRolesForOrganizationUseCase;
import com.clavaris.organization.application.usecase.updateworkspacerole.UpdateWorkspaceRoleCommand;
import com.clavaris.organization.application.usecase.updateworkspacerole.UpdateWorkspaceRoleUseCase;
import com.clavaris.organization.application.usecase.updateworkspacerole.WorkspaceRoleCycleException;
import com.clavaris.organization.domain.model.WorkspaceRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADR-0027 §4: {@code /api/v1/admin/organizations/{organizationId}/workspace-roles} — list, create,
 * update, delete. Same single-controller-per-resource shape {@code
 * AccessRestrictionEntriesController} already establishes. The reserved bootstrap role ({@code
 * CreateWorkspaceService}) is never created/deleted through this controller — {@link
 * CannotDeleteReservedWorkspaceRoleException} is the only guard against deleting it; renaming or
 * changing its (non-reserved-namespace) permissions through {@code update} is allowed.
 */
@SuppressWarnings("PMD.ExcessiveImports")
@RestController
@RequestMapping("/api/v1/admin/organizations/{organizationId}/workspace-roles")
class WorkspaceRolesController {

  private final ListWorkspaceRolesForOrganizationUseCase listRoles;
  private final CreateWorkspaceRoleUseCase createRole;
  private final UpdateWorkspaceRoleUseCase updateRole;
  private final DeleteWorkspaceRoleUseCase deleteRole;

  /* package */ WorkspaceRolesController(
      final ListWorkspaceRolesForOrganizationUseCase listRoles,
      final CreateWorkspaceRoleUseCase createRole,
      final UpdateWorkspaceRoleUseCase updateRole,
      final DeleteWorkspaceRoleUseCase deleteRole) {
    this.listRoles = listRoles;
    this.createRole = createRole;
    this.updateRole = updateRole;
    this.deleteRole = deleteRole;
  }

  @Operation(summary = "List an Organization's own WorkspaceRoles (ADR-0027)")
  @ApiResponse(responseCode = "200", description = "Possibly empty list")
  @GetMapping
  /* package */ List<WorkspaceRoleResponse> list(@PathVariable final UUID organizationId) {
    return listRoles.handle(new ListWorkspaceRolesForOrganizationQuery(organizationId)).stream()
        .map(WorkspaceRoleResponse::from)
        .toList();
  }

  // Three exits (404 unknown Organization/parentRoleId, 409 duplicate name, 201 success) — same
  // rationale as every other admin-mutation controller in this codebase.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @Operation(summary = "Create a WorkspaceRole within this Organization (ADR-0027)")
  @ApiResponse(responseCode = "201", description = "WorkspaceRole created")
  @ApiResponse(
      responseCode = "404",
      description =
          "No Organization exists with the given id, or parentRoleId doesn't"
              + " reference a WorkspaceRole belonging to it")
  @ApiResponse(responseCode = "409", description = "A WorkspaceRole with this name already exists")
  @PostMapping
  /* package */ ResponseEntity<WorkspaceRoleResponse> create(
      @PathVariable final UUID organizationId,
      @Valid @RequestBody final CreateWorkspaceRoleRequest request,
      final Authentication authentication) {
    final WorkspaceRole role;
    try {
      role =
          createRole.handle(
              new CreateWorkspaceRoleCommand(
                  organizationId,
                  request.name(),
                  request.parentRoleId(),
                  request.permissions(),
                  AuditActor.platformClient(authentication.getName())));
    } catch (final OrganizationNotFoundException | WorkspaceRoleNotFoundException _) {
      return ResponseEntity.notFound().build();
    } catch (final DuplicateWorkspaceRoleNameException _) {
      return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }
    return ResponseEntity.status(HttpStatus.CREATED).body(WorkspaceRoleResponse.from(role));
  }

  // Four exits (404 unknown role/parentRoleId, 409 duplicate name, 422 cycle, 200 success) — same
  // rationale as every other multi-outcome handler in this codebase.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @Operation(summary = "Update a WorkspaceRole's name, permissions, or parent (ADR-0027)")
  @ApiResponse(responseCode = "200", description = "WorkspaceRole updated")
  @ApiResponse(
      responseCode = "404",
      description =
          "No WorkspaceRole exists with the given id, or parentRoleId doesn't"
              + " reference one belonging to the same Organization")
  @ApiResponse(responseCode = "409", description = "A WorkspaceRole with this name already exists")
  @ApiResponse(
      responseCode = "422",
      description = "This parentRoleId would create a cycle in the role hierarchy")
  @PatchMapping("/{roleId}")
  /* package */ ResponseEntity<WorkspaceRoleResponse> update(
      @PathVariable final UUID organizationId,
      @PathVariable final UUID roleId,
      @Valid @RequestBody final UpdateWorkspaceRoleRequest request,
      final Authentication authentication) {
    final WorkspaceRole role;
    try {
      role =
          updateRole.handle(
              new UpdateWorkspaceRoleCommand(
                  roleId,
                  request.name(),
                  request.parentRoleId(),
                  request.permissions(),
                  AuditActor.platformClient(authentication.getName())));
    } catch (final WorkspaceRoleNotFoundException _) {
      return ResponseEntity.notFound().build();
    } catch (final DuplicateWorkspaceRoleNameException _) {
      return ResponseEntity.status(HttpStatus.CONFLICT).build();
    } catch (final WorkspaceRoleCycleException _) {
      return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).build();
    }
    return ResponseEntity.ok(WorkspaceRoleResponse.from(role));
  }

  // Three exits (404 unknown role, 409 reserved/still-assigned, 204 success) — same rationale as
  // every other multi-outcome handler in this codebase.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @Operation(summary = "Delete a WorkspaceRole (ADR-0027 §5)")
  @ApiResponse(responseCode = "204", description = "Deleted")
  @ApiResponse(responseCode = "404", description = "No WorkspaceRole exists with the given id")
  @ApiResponse(
      responseCode = "409",
      description = "This role is reserved, or still assigned to at least one member")
  @DeleteMapping("/{roleId}")
  /* package */ ResponseEntity<Void> delete(
      @PathVariable final UUID organizationId,
      @PathVariable final UUID roleId,
      final Authentication authentication) {
    try {
      deleteRole.handle(
          new DeleteWorkspaceRoleCommand(
              roleId, AuditActor.platformClient(authentication.getName())));
    } catch (final WorkspaceRoleNotFoundException _) {
      return ResponseEntity.notFound().build();
    } catch (final CannotDeleteReservedWorkspaceRoleException
        | WorkspaceRoleStillAssignedException _) {
      return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }
    return ResponseEntity.noContent().build();
  }
}
