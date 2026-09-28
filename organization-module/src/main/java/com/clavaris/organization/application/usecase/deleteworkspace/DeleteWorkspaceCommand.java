package com.clavaris.organization.application.usecase.deleteworkspace;

import com.clavaris.common.domain.model.AuditActor;
import java.util.UUID;

/**
 * {@code workspaceId} carries no {@code organizationId} of its own — same anti-enumeration
 * reasoning {@code DeleteWorkspaceRoleCommand}'s own Javadoc documents for an identical shape: the
 * caller (dashboard's own {@code PlatformWorkspaceController}) already resolves ownership via
 * {@code GetWorkspaceForOrganizationUseCase} before this command is ever built, so a workspaceId
 * belonging to a different Organization never reaches this class.
 */
public record DeleteWorkspaceCommand(UUID workspaceId, AuditActor actor) {}
