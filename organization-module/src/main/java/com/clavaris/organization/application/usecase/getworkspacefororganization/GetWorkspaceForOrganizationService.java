package com.clavaris.organization.application.usecase.getworkspacefororganization;

import com.clavaris.organization.application.usecase.createworkspace.WorkspaceRepository;
import com.clavaris.organization.domain.model.Workspace;
import java.util.Optional;

public class GetWorkspaceForOrganizationService implements GetWorkspaceForOrganizationUseCase {

  private final WorkspaceRepository workspaces;

  public GetWorkspaceForOrganizationService(final WorkspaceRepository workspaces) {
    this.workspaces = workspaces;
  }

  @Override
  public Optional<Workspace> handle(final GetWorkspaceForOrganizationQuery query) {
    return workspaces
        .findById(query.workspaceId())
        .filter(workspace -> workspace.organizationId().equals(query.organizationId()));
  }
}
