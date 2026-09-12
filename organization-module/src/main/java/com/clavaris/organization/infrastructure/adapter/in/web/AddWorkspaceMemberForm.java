package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.organization.domain.model.WorkspaceRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Web-layer form object for the dashboard's own "add member" form on the Workspace-detail page —
 * same {@code AddWorkspaceMemberRequest}-is-the-REST-API's-own-DTO split {@link
 * CreateOrganizationForm}'s own Javadoc documents. {@code role} defaults to {@link
 * WorkspaceRole#MEMBER} when the select is left on its default option (BR-WS-05).
 */
// PMD.DataClass: a plain web-layer form bean is *supposed* to be just fields + getters/setters —
// same "expected here, not a smell to fix" rationale AccountEntity's own identical suppression
// documents for a JPA entity.
@SuppressWarnings("PMD.DataClass")
public class AddWorkspaceMemberForm {

  @NotBlank(message = "Email is required")
  @Email(message = "Must be a valid email address")
  private String email;

  private WorkspaceRole role = WorkspaceRole.MEMBER;

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

  public WorkspaceRole getRole() {
    return role;
  }

  public void setRole(final WorkspaceRole role) {
    this.role = role == null ? WorkspaceRole.MEMBER : role;
  }
}
