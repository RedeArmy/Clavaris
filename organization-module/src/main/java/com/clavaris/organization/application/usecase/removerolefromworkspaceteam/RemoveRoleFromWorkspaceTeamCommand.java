package com.clavaris.organization.application.usecase.removerolefromworkspaceteam;

import com.clavaris.common.domain.model.AuditActor;
import java.util.UUID;

/**
 * SDE-III review, 2026-09-27 (real IDOR found and closed): {@code workspaceId} is the caller's own
 * already-ownership-verified Workspace — {@link RemoveRoleFromWorkspaceTeamService} rejects a
 * {@code teamId} whose real {@code workspaceId} doesn't match it, same anti-enumeration reasoning
 * {@code DeleteWorkspaceTeamCommand}'s own identical addition documents.
 */
public record RemoveRoleFromWorkspaceTeamCommand(
    UUID workspaceId, UUID teamId, UUID roleId, AuditActor actor) {}
