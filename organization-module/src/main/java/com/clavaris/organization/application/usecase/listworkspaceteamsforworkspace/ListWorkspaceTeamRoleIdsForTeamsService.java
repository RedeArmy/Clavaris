package com.clavaris.organization.application.usecase.listworkspaceteamsforworkspace;

import com.clavaris.organization.application.usecase.createworkspaceteam.WorkspaceTeamRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ListWorkspaceTeamRoleIdsForTeamsService
    implements ListWorkspaceTeamRoleIdsForTeamsUseCase {

  private final WorkspaceTeamRepository teams;

  public ListWorkspaceTeamRoleIdsForTeamsService(final WorkspaceTeamRepository teams) {
    this.teams = teams;
  }

  @Override
  public Map<UUID, List<UUID>> handle(final ListWorkspaceTeamRoleIdsForTeamsQuery query) {
    return teams.findRoleIdsByTeamIds(query.teamIds());
  }
}
