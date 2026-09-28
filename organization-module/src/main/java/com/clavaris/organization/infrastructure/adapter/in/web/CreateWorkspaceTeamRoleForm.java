package com.clavaris.organization.infrastructure.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Web-layer form object for the Workspace-detail page's own "create a new role" popup —
 * deliberately narrower than {@link CreateWorkspaceRoleForm} (no {@code parentRoleId}/{@code
 * permissionsText}): those stay exclusively on the full Configure &gt; Workspace Roles page, this
 * popup only covers the common "name a role and put it on a team" path. {@code teamId} is required
 * — this popup composes {@link
 * com.clavaris.organization.application.usecase.createworkspacerole.CreateWorkspaceRoleUseCase}
 * with {@link
 * com.clavaris.organization.application.usecase.addroletoworkspaceteam.AddRoleToWorkspaceTeamUseCase}
 * in one submit — {@link PlatformWorkspaceController#createRole} is where those two use cases
 * actually get composed.
 */
// PMD.DataClass: a plain web-layer form bean is *supposed* to be just fields + getters/setters —
// same "expected here, not a smell to fix" rationale WorkspaceRoleForm's own identical suppression
// documents.
@SuppressWarnings("PMD.DataClass")
public class CreateWorkspaceTeamRoleForm {

  @NotBlank(message = "Name is required")
  @Size(max = 255, message = "Name must be at most 255 characters")
  private String name;

  @NotNull(message = "Team is required")
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
