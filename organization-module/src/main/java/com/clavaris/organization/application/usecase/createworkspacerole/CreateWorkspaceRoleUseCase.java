package com.clavaris.organization.application.usecase.createworkspacerole;

import com.clavaris.organization.domain.model.WorkspaceRole;

/** Inbound port for {@code POST /api/v1/admin/organizations/{organizationId}/workspace-roles}. */
@FunctionalInterface
public interface CreateWorkspaceRoleUseCase {

  WorkspaceRole handle(CreateWorkspaceRoleCommand command);
}
