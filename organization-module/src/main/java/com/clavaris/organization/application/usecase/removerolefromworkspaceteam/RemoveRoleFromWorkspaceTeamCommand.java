package com.clavaris.organization.application.usecase.removerolefromworkspaceteam;

import com.clavaris.common.domain.model.AuditActor;
import java.util.UUID;

public record RemoveRoleFromWorkspaceTeamCommand(UUID teamId, UUID roleId, AuditActor actor) {}
