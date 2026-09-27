package com.clavaris.organization.infrastructure.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Shared name/parentRoleId/permissionsText fields for a workspace-role-shaped web form (ADR-0027
 * Slice 5) — reused by {@link CreateWorkspaceRoleForm} and {@link UpdateWorkspaceRoleForm}, which
 * were byte-for-byte identical apart from their class name (flagged as duplicated code), same
 * extraction {@code EmailPasswordForm} already establishes for {@code LoginForm}/{@code
 * PlatformLoginForm}'s own identical shape. Public (not package-private) so Spring/Thymeleaf's
 * reflective property binding on the concrete subclasses never has to reason about an
 * inherited-from-a-non-public-superclass edge case — abstract, so nothing ever binds to it directly
 * (hence no abstract method of its own — PMD.AbstractClassWithoutAbstractMethod suppressed: the
 * point of abstractness here is "never instantiate this on its own," not "force subclasses to
 * implement something"). {@code permissionsText} is one opaque, consumer-defined permission string
 * per line — Clavaris never interprets these (same posture as {@code WorkspaceRole} itself, see its
 * own Javadoc) — parsed into a {@code Set<String>} by {@link PlatformWorkspaceRoleController}
 * before building the command, never here.
 */
@SuppressWarnings({"PMD.DataClass", "PMD.AbstractClassWithoutAbstractMethod"})
public abstract class WorkspaceRoleForm {

  @NotBlank(message = "Name is required")
  @Size(max = 255, message = "Name must be at most 255 characters")
  private String name;

  private UUID parentRoleId;

  private String permissionsText;

  protected WorkspaceRoleForm() {
    // Intentionally empty — only ever invoked via a concrete subclass's super().
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
