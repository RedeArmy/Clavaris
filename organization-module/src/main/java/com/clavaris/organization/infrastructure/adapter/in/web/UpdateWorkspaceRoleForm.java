package com.clavaris.organization.infrastructure.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Web-layer form object for the dashboard's own "edit workspace role" page (ADR-0027 Slice 5) —
 * same shape as {@link CreateWorkspaceRoleForm}, a separate class rather than a shared superclass
 * because the two pages bind to genuinely distinct {@code th:object} attribute names and the REST
 * API's own {@code CreateWorkspaceRoleRequest}/{@code UpdateWorkspaceRoleRequest} split already
 * establishes the same one-form-object-per-action convention this module follows.
 *
 * <p>Full-replace, not a merge-patch — same convention {@code UpdateWorkspaceRoleCommand}'s own
 * Javadoc documents: the rendered form always carries the role's complete current state, and
 * submitting it always sends the complete new desired state, never a partial diff.
 */
@SuppressWarnings("PMD.DataClass")
public class UpdateWorkspaceRoleForm {

  @NotBlank(message = "Name is required")
  @Size(max = 255, message = "Name must be at most 255 characters")
  private String name;

  private UUID parentRoleId;

  private String permissionsText;

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  public UpdateWorkspaceRoleForm() {
    // Intentionally empty.
  }

  public String getName() {
    return name;
  }

  public void setName(final String name) {
    this.name = name;
  }

  public UUID getParentRoleId() {
    return parentRoleId;
  }

  public void setParentRoleId(final UUID parentRoleId) {
    this.parentRoleId = parentRoleId;
  }

  public String getPermissionsText() {
    return permissionsText;
  }

  public void setPermissionsText(final String permissionsText) {
    this.permissionsText = permissionsText;
  }
}
