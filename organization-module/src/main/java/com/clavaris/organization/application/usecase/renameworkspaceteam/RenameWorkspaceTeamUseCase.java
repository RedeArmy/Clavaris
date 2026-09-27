package com.clavaris.organization.application.usecase.renameworkspaceteam;

import com.clavaris.organization.domain.model.WorkspaceTeam;

@FunctionalInterface
public interface RenameWorkspaceTeamUseCase {

  WorkspaceTeam handle(RenameWorkspaceTeamCommand command);
}
