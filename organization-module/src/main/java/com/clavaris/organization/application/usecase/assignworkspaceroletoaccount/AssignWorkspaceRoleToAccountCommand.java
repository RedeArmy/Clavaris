package com.clavaris.organization.application.usecase.assignworkspaceroletoaccount;

import com.clavaris.common.domain.model.AuditActor;
import java.util.UUID;

/**
 * @param roleId must always reference a real role — unlike {@code
 *     ChangeWorkspaceMemberRoleCommand#newRoleId}, this use case's whole purpose is "assign a
 *     role," never "unassign one" (that stays exclusively {@code
 *     ChangeWorkspaceMemberRoleUseCase}'s job, untouched by this addition).
 */
public record AssignWorkspaceRoleToAccountCommand(
    UUID workspaceId, UUID accountId, UUID roleId, AuditActor actor) {}
