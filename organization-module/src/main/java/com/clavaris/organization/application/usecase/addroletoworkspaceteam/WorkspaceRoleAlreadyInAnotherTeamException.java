package com.clavaris.organization.application.usecase.addroletoworkspaceteam;

import java.util.UUID;

/** ADR-0028 §2: a role may belong to at most one team per Workspace. */
public final class WorkspaceRoleAlreadyInAnotherTeamException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public WorkspaceRoleAlreadyInAnotherTeamException(final UUID roleId, final UUID existingTeamId) {
    super(
        "WorkspaceRole "
            + roleId
            + " already belongs to team "
            + existingTeamId
            + " in this Workspace — remove it from that team first");
  }
}
