package com.clavaris.organization.application.usecase.removerolefromworkspaceteam;

@FunctionalInterface
public interface RemoveRoleFromWorkspaceTeamUseCase {

  void handle(RemoveRoleFromWorkspaceTeamCommand command);
}
