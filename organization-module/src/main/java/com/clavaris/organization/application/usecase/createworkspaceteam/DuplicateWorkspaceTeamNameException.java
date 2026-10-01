package com.clavaris.organization.application.usecase.createworkspaceteam;

/** ADR-0028: team names are scoped for uniqueness within their own Workspace. */
public final class DuplicateWorkspaceTeamNameException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public DuplicateWorkspaceTeamNameException(final String name) {
    super("A WorkspaceTeam named '" + name + "' already exists in this Workspace");
  }

  /**
   * TD-SEC-060: same message, plus the low-level exception that revealed the conflict (a lost race
   * against {@code ux_workspace_teams_workspace_id_name}) — preserves its stack trace instead of
   * discarding it, same precedent {@code EmailAlreadyRegisteredException}'s own two-constructor
   * shape already establishes.
   */
  public DuplicateWorkspaceTeamNameException(final String name, final Throwable cause) {
    super("A WorkspaceTeam named '" + name + "' already exists in this Workspace", cause);
  }
}
