package com.clavaris.organization.application.usecase.createworkspaceteam;

import com.clavaris.common.domain.model.AuditActor;
import java.util.UUID;

public record CreateWorkspaceTeamCommand(UUID workspaceId, String name, AuditActor actor) {}
