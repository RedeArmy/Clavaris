package com.clavaris.organization.application.usecase.updateworkspacerole;

import com.clavaris.organization.domain.model.WorkspaceRole;

/**
 * Inbound port for {@code PATCH
 * /api/v1/admin/organizations/{organizationId}/workspace-roles/{roleId}}.
 */
@FunctionalInterface
public interface UpdateWorkspaceRoleUseCase {

  WorkspaceRole handle(UpdateWorkspaceRoleCommand command);
}
