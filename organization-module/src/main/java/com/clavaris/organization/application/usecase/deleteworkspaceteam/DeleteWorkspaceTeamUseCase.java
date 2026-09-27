package com.clavaris.organization.application.usecase.deleteworkspaceteam;

@FunctionalInterface
public interface DeleteWorkspaceTeamUseCase {

  void handle(DeleteWorkspaceTeamCommand command);
}
