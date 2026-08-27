package com.clavaris.organization.application.usecase.changeworkspacememberrole;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.organization.domain.model.WorkspaceRole;
import java.util.UUID;

public record ChangeWorkspaceMemberRoleCommand(
    UUID workspaceId, UUID accountId, WorkspaceRole newRole, AuditActor actor) {}
