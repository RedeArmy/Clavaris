package com.clavaris.organization.application.usecase.deleteworkspaceteam;

import com.clavaris.common.domain.model.AuditActor;
import java.util.UUID;

/**
 * SDE-III review, 2026-09-27 (real IDOR found and closed): {@code workspaceId} is the caller's own
 * already-ownership-verified Workspace — {@link DeleteWorkspaceTeamService} rejects a {@code
 * teamId} whose real {@code workspaceId} doesn't match it, the same anti-enumeration check every
 * other cross-Organization-reachable resource in this codebase already applies. Without it, any
 * caller who owns any Organization could delete any other Organization's own team by guessing its
 * id — the controller's own ownership checks never reached this far down.
 */
public record DeleteWorkspaceTeamCommand(UUID workspaceId, UUID teamId, AuditActor actor) {}
