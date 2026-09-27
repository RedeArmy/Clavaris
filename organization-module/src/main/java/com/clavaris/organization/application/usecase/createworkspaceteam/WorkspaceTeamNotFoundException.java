package com.clavaris.organization.application.usecase.createworkspaceteam;

import java.util.UUID;

public final class WorkspaceTeamNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public WorkspaceTeamNotFoundException(final UUID teamId) {
    super("No WorkspaceTeam found with id " + teamId);
  }
}
