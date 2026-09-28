package com.clavaris.organization.infrastructure.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Web-layer form object for the Workspace-detail page's own "create a new role" popup —
 * deliberately narrower than {@link CreateWorkspaceRoleForm} (no {@code parentRoleId}/{@code
 * permissionsText}): those stay exclusively on the full Configure &gt; Workspace Roles page, this
 * popup only covers the common "name a role, optionally put it on a team" path. {@code teamId} is
 * optional — live UX request, 2026-09-28: a role with no team stays a fully supported first-class
 * state (the "Without Team" bucket every consumer-facing view already renders — see {@code
 * DeleteWorkspaceTeamService}'s own Javadoc on "the roles themselves survive, becoming ungrouped",
 * and the reserved Admin role, which has never been assigned to any team). When {@code teamId} is
 * present, this popup composes {@link
 * com.clavaris.organization.application.usecase.createworkspacerole.CreateWorkspaceRoleUseCase}
 * with {@link
 * com.clavaris.organization.application.usecase.addroletoworkspaceteam.AddRoleToWorkspaceTeamUseCase}
 * in one submit; when absent, only the first — {@link PlatformWorkspaceController#createRole} is
 * where that composition (or lack of it) actually happens.
 */
// PMD.DataClass: a plain web-layer form bean is *supposed* to be just fields + getters/setters —
// same "expected here, not a smell to fix" rationale WorkspaceRoleForm's own identical suppression
// documents.
@SuppressWarnings("PMD.DataClass")
public class CreateWorkspaceTeamRoleForm {

  @NotBlank(message = "Name is required")
  @Size(max = 255, message = "Name must be at most 255 characters")
  private String name;

  private UUID teamId;

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  public CreateWorkspaceTeamRoleForm() {
    // Intentionally empty.
  }

  public String getName() {
    return name;
  }

  public void setName(final String name) {
    this.name = name;
  }

  public UUID getTeamId() {
    return teamId;
  }

  public void setTeamId(final UUID teamId) {
    this.teamId = teamId;
  }
}
