package com.clavaris.organization.application.usecase.listworkspaceteamsforworkspace;

import com.clavaris.organization.application.usecase.createworkspaceteam.WorkspaceTeamRepository;
import java.util.List;
import java.util.UUID;

public class ListWorkspaceTeamRoleIdsService implements ListWorkspaceTeamRoleIdsUseCase {

  private final WorkspaceTeamRepository teams;

  public ListWorkspaceTeamRoleIdsService(final WorkspaceTeamRepository teams) {
    this.teams = teams;
  }

  @Override
  public List<UUID> handle(final ListWorkspaceTeamRoleIdsQuery query) {
    return teams.findRoleIdsByTeamId(query.teamId());
  }
}
