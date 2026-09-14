package com.clavaris.organization.application.usecase.listworkspacesfororganizationpaged;

import com.clavaris.common.domain.model.KeysetPage;
import com.clavaris.organization.application.usecase.createworkspace.WorkspaceRepository;
import com.clavaris.organization.domain.model.Workspace;

public class ListWorkspacesForOrganizationPagedService
    implements ListWorkspacesForOrganizationPagedUseCase {

  private final WorkspaceRepository workspaces;

  public ListWorkspacesForOrganizationPagedService(final WorkspaceRepository workspaces) {
    this.workspaces = workspaces;
  }

  @Override
  public KeysetPage<Workspace> handle(final ListWorkspacesForOrganizationPagedQuery query) {
    return workspaces.findKeysetPageByOrganizationId(query.organizationId(), query.pageRequest());
  }
}
