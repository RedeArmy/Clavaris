package com.clavaris.organization.infrastructure.adapter.in.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * HTTP request body for {@code POST /api/v1/admin/workspaces/{workspaceId}/members}. {@code roleId}
 * (ADR-0027 — replaces the old fixed-enum {@code role}) is required: unlike the old two-value enum,
 * there's no fixed default {@code WorkspaceRole} to fall back to when omitted — see {@code
 * AddWorkspaceMemberService}'s own validation for the "must belong to this Workspace's own
 * Organization" check.
 */
public record AddWorkspaceMemberRequest(@NotBlank @Email String email, @NotNull UUID roleId) {}
