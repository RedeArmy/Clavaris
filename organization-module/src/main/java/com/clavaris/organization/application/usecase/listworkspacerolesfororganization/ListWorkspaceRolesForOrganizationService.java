package com.clavaris.organization.application.usecase.listworkspacerolesfororganization;

import com.clavaris.organization.application.usecase.createworkspace.WorkspaceRoleRepository;
import com.clavaris.organization.domain.model.WorkspaceRole;
import java.util.List;

public class ListWorkspaceRolesForOrganizationService
    implements ListWorkspaceRolesForOrganizationUseCase {

  private final WorkspaceRoleRepository roles;

  public ListWorkspaceRolesForOrganizationService(final WorkspaceRoleRepository roles) {
    this.roles = roles;
  }

  @Override
  public List<WorkspaceRole> handle(final ListWorkspaceRolesForOrganizationQuery query) {
    return roles.findAllByOrganizationId(query.organizationId());
  }
}
