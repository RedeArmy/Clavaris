package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.organization.domain.model.WorkspaceRole;
import jakarta.validation.constraints.NotNull;

public record ChangeWorkspaceMemberRoleRequest(@NotNull WorkspaceRole role) {}
