package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.organization.application.usecase.createworkspace.CreateWorkspaceCommand;
import com.clavaris.organization.application.usecase.createworkspace.CreateWorkspaceUseCase;
import com.clavaris.organization.application.usecase.createworkspace.OrganizationNotFoundException;
import com.clavaris.organization.domain.model.Workspace;
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
 * ADR-0010 §3 addendum: {@code POST /api/v1/admin/organizations/{organizationId}/workspaces} —
 * operator/consumer-application only, same tier as every other {@code /api/v1/admin/**} controller.
 */
@RestController
class CreateWorkspaceController {

  private final CreateWorkspaceUseCase useCase;

  /* package */ CreateWorkspaceController(final CreateWorkspaceUseCase useCase) {
    this.useCase = useCase;
  }

  // Two exits (404 on an unknown organizationId, 201 on success) — same rationale as
  // CreateOrganizationController's own identical suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @Operation(summary = "Create a Workspace within this Organization (ADR-0010 §3)")
  @ApiResponse(responseCode = "201", description = "Workspace created")
  @ApiResponse(responseCode = "404", description = "No Organization exists with the given id")
  @PostMapping("/api/v1/admin/organizations/{organizationId}/workspaces")
  /* package */ ResponseEntity<WorkspaceResponse> create(
      @PathVariable final UUID organizationId,
      @Valid @RequestBody final CreateWorkspaceRequest request,
      final Authentication authentication) {
    final Workspace workspace;
    try {
      workspace =
          useCase.handle(
              new CreateWorkspaceCommand(
                  organizationId,
                  request.name(),
                  AuditActor.platformClient(authentication.getName())));
    } catch (final OrganizationNotFoundException _) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.status(HttpStatus.CREATED).body(WorkspaceResponse.from(workspace));
  }
}
