package com.clavaris.organization.application.usecase.listworkspacesfororganization;

import com.clavaris.organization.application.usecase.createworkspace.WorkspaceRepository;
import com.clavaris.organization.domain.model.Workspace;
import java.util.List;

public class ListWorkspacesForOrganizationService implements ListWorkspacesForOrganizationUseCase {

  private final WorkspaceRepository workspaces;

  public ListWorkspacesForOrganizationService(final WorkspaceRepository workspaces) {
    this.workspaces = workspaces;
  }

  @Override
  public List<Workspace> handle(final ListWorkspacesForOrganizationQuery query) {
    return workspaces.findAllByOrganizationId(query.organizationId());
  }
}
