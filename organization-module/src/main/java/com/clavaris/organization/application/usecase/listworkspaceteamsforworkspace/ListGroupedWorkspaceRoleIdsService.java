package com.clavaris.organization.application.usecase.listworkspaceteamsforworkspace;

import com.clavaris.organization.application.usecase.createworkspaceteam.WorkspaceTeamRepository;
import java.util.Set;
import java.util.UUID;

public class ListGroupedWorkspaceRoleIdsService implements ListGroupedWorkspaceRoleIdsUseCase {

  private final WorkspaceTeamRepository teams;

  public ListGroupedWorkspaceRoleIdsService(final WorkspaceTeamRepository teams) {
    this.teams = teams;
  }

  @Override
  public Set<UUID> handle(final ListGroupedWorkspaceRoleIdsQuery query) {
    return teams.findAllGroupedRoleIdsForWorkspace(query.workspaceId());
  }
}
