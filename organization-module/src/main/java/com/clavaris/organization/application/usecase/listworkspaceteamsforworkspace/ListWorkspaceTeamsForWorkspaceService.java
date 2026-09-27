package com.clavaris.organization.application.usecase.listworkspaceteamsforworkspace;

import com.clavaris.organization.application.usecase.createworkspaceteam.WorkspaceTeamRepository;
import com.clavaris.organization.domain.model.WorkspaceTeam;
import java.util.List;

public class ListWorkspaceTeamsForWorkspaceService
    implements ListWorkspaceTeamsForWorkspaceUseCase {

  private final WorkspaceTeamRepository teams;

  public ListWorkspaceTeamsForWorkspaceService(final WorkspaceTeamRepository teams) {
    this.teams = teams;
  }

  @Override
  public List<WorkspaceTeam> handle(final ListWorkspaceTeamsForWorkspaceQuery query) {
    return teams.findAllByWorkspaceId(query.workspaceId());
  }
}
