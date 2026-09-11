package com.clavaris.organization.infrastructure.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Web-layer form object for the dashboard's own "create workspace" form on the Organization-detail
 * page — same {@code CreateWorkspaceRequest}-is-the-REST-API's-own-DTO split {@link
 * CreateOrganizationForm}'s own Javadoc documents.
 *
 * <p>{@code @Size(max = 255)}: matches the {@code workspaces.name} column, same enforce-it-at-
 * every-layer discipline {@link CreateOrganizationForm} already established.
 */
public class CreateWorkspaceForm {

  @NotBlank(message = "Name is required")
  @Size(max = 255, message = "Name must be at most 255 characters")
  private String name;

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  public CreateWorkspaceForm() {
    // Intentionally empty.
  }

  public String getName() {
    return name;
  }

  public void setName(final String name) {
    this.name = name;
  }
}
