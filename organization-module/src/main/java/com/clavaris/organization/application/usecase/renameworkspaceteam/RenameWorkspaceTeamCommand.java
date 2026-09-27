package com.clavaris.organization.application.usecase.renameworkspaceteam;

import com.clavaris.common.domain.model.AuditActor;
import java.util.UUID;

public record RenameWorkspaceTeamCommand(UUID teamId, String newName, AuditActor actor) {}
