package com.clavaris.organization.application.usecase.listworkspacesfororganizationpaged;

import com.clavaris.common.domain.model.Page;
import com.clavaris.organization.application.usecase.createworkspace.WorkspaceRepository;
import com.clavaris.organization.domain.model.Workspace;

public class ListWorkspacesForOrganizationPagedService
    implements ListWorkspacesForOrganizationPagedUseCase {

  private final WorkspaceRepository workspaces;

  public ListWorkspacesForOrganizationPagedService(final WorkspaceRepository workspaces) {
    this.workspaces = workspaces;
  }

  @Override
  public Page<Workspace> handle(final ListWorkspacesForOrganizationPagedQuery query) {
    return workspaces.findPageByOrganizationId(query.organizationId(), query.pageRequest());
  }
}
