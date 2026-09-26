package com.clavaris.organization.application.usecase.changeworkspacememberrole;

import com.clavaris.common.domain.model.AuditActor;
import java.util.UUID;

/**
 * @param newRoleId ADR-0027 — a {@code WorkspaceRole} id to reassign the membership to, or {@code
 *     null} to unassign it entirely (an explicitly allowed "no role" state, ADR-0027 §5) — unlike
 *     {@link
 *     com.clavaris.organization.application.usecase.addworkspacemember.AddWorkspaceMemberCommand#roleId},
 *     which must always reference a real role.
 */
public record ChangeWorkspaceMemberRoleCommand(
    UUID workspaceId, UUID accountId, UUID newRoleId, AuditActor actor) {}
