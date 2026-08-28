package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.organization.domain.model.WorkspaceRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * HTTP request body for {@code POST /api/v1/admin/workspaces/{workspaceId}/members}. {@code role}
 * is optional — {@link #role()} defaults to {@link WorkspaceRole#MEMBER} when omitted (BR-WS-05:
 * the only two values this system knows about).
 */
public record AddWorkspaceMemberRequest(@NotBlank @Email String email, WorkspaceRole role) {

  public WorkspaceRole roleOrDefault() {
    return role == null ? WorkspaceRole.MEMBER : role;
  }
}
