package com.clavaris.organization.application.usecase.deleteworkspaceteam;

import com.clavaris.common.domain.model.AuditActor;
import java.util.UUID;

public record DeleteWorkspaceTeamCommand(UUID teamId, AuditActor actor) {}
