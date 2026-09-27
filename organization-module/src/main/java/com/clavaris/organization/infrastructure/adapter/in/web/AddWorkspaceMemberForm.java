package com.clavaris.organization.infrastructure.adapter.in.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Web-layer form object for the dashboard's own "add member" form on the Workspace-detail page —
 * same {@code AddWorkspaceMemberRequest}-is-the-REST-API's-own-DTO split {@link
 * CreateOrganizationForm}'s own Javadoc documents. {@code roleId} (ADR-0027 — replaces the old
 * fixed-enum {@code role}) is a required select over this Organization's own {@code WorkspaceRole}
 * list, populated by the controller — unlike the old enum, there's no fixed default value to fall
 * back to when the select is left unset.
 */
// PMD.DataClass: a plain web-layer form bean is *supposed* to be just fields + getters/setters —
// same "expected here, not a smell to fix" rationale AccountEntity's own identical suppression
// documents for a JPA entity.
@SuppressWarnings("PMD.DataClass")
public class AddWorkspaceMemberForm {

  @NotBlank(message = "Email is required")
  @Email(message = "Must be a valid email address")
  private String email;

  @NotNull(message = "Role is required")
  private UUID roleId;

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  public AddWorkspaceMemberForm() {
    // Intentionally empty.
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(final String email) {
    this.email = email;
  }

  public UUID getRoleId() {
    return roleId;
  }

  public void setRoleId(final UUID roleId) {
    this.roleId = roleId;
  }
}
