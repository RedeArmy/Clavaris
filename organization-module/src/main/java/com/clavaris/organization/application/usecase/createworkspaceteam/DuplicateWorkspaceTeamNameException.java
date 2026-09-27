package com.clavaris.organization.application.usecase.createworkspaceteam;

/** ADR-0028: team names are scoped for uniqueness within their own Workspace. */
public final class DuplicateWorkspaceTeamNameException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public DuplicateWorkspaceTeamNameException(final String name) {
    super("A WorkspaceTeam named '" + name + "' already exists in this Workspace");
  }
}
