package com.clavaris.organization.application.usecase.deleteworkspacerole;

import com.clavaris.common.domain.model.AuditActor;
import java.util.UUID;

public record DeleteWorkspaceRoleCommand(UUID roleId, AuditActor actor) {}
