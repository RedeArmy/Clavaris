package com.clavaris.organization.application.usecase.addroletoworkspaceteam;

import com.clavaris.common.domain.model.AuditActor;
import java.util.UUID;

public record AddRoleToWorkspaceTeamCommand(UUID teamId, UUID roleId, AuditActor actor) {}
