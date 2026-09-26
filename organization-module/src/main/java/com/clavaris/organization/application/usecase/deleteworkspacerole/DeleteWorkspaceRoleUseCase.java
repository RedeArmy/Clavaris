package com.clavaris.organization.application.usecase.deleteworkspacerole;

/**
 * Inbound port for {@code DELETE
 * /api/v1/admin/organizations/{organizationId}/workspace-roles/{roleId}}.
 */
@FunctionalInterface
public interface DeleteWorkspaceRoleUseCase {

  void handle(DeleteWorkspaceRoleCommand command);
}
