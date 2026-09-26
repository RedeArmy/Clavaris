package com.clavaris.organization.application.usecase.listworkspacerolesfororganization;

import com.clavaris.organization.domain.model.WorkspaceRole;
import java.util.List;

/**
 * ADR-0027: the dashboard's own member-add/role-change forms need this Organization's full {@code
 * WorkspaceRole} list to populate a role selector — same read-only "list for a dropdown" shape
 * {@code ListWorkspacesForOrganizationUseCase} already establishes.
 */
@FunctionalInterface
public interface ListWorkspaceRolesForOrganizationUseCase {

  List<WorkspaceRole> handle(ListWorkspaceRolesForOrganizationQuery query);
}
