package com.clavaris.organization.application.usecase.createworkspaceteam;

import com.clavaris.organization.domain.model.WorkspaceTeam;

@FunctionalInterface
public interface CreateWorkspaceTeamUseCase {

  WorkspaceTeam handle(CreateWorkspaceTeamCommand command);
}
