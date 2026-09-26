package com.clavaris.organization.infrastructure.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Web-layer form object for the dashboard's own "new workspace role" page (ADR-0027 Slice 5) — same
 * {@code CreateWorkspaceRoleRequest}-is-the-REST-API's-own-DTO split {@link CreateWorkspaceForm}'s
 * own Javadoc documents. {@code permissionsText} is one opaque, consumer-defined permission string
 * per line — Clavaris never interprets these (same posture as {@code WorkspaceRole} itself, see its
 * own Javadoc) — parsed into a {@code Set<String>} by {@link PlatformWorkspaceRoleController}
 * before building the command, never here (a plain data holder, same shape every other form in this
 * package keeps).
 */
// PMD.DataClass: a plain web-layer form bean is *supposed* to be just fields + getters/setters —
// same "expected here, not a smell to fix" rationale AddWorkspaceMemberForm's own identical
// suppression documents.
@SuppressWarnings("PMD.DataClass")
public class CreateWorkspaceRoleForm {

  @NotBlank(message = "Name is required")
  @Size(max = 255, message = "Name must be at most 255 characters")
  private String name;

  private UUID parentRoleId;

  private String permissionsText;

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  public CreateWorkspaceRoleForm() {
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
