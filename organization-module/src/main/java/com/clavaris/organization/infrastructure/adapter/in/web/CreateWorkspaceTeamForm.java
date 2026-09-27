package com.clavaris.organization.infrastructure.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Web-layer form object for the Workspace-detail page's own "create team" form (ADR-0028). */
public class CreateWorkspaceTeamForm {

  @NotBlank(message = "Name is required")
  @Size(max = 255, message = "Name must be at most 255 characters")
  private String name;

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  public CreateWorkspaceTeamForm() {
    // Intentionally empty.
  }

  public String getName() {
    return name;
  }

  public void setName(final String name) {
    this.name = name;
  }
}
