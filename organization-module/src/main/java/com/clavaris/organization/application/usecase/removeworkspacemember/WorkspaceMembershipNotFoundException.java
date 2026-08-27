package com.clavaris.organization.application.usecase.removeworkspacemember;

import java.util.UUID;

/** Same rationale as this module's sibling in {@code changeworkspacememberrole}. */
public final class WorkspaceMembershipNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public WorkspaceMembershipNotFoundException(final UUID workspaceId, final UUID accountId) {
    super("No membership exists for account " + accountId + " in workspace " + workspaceId);
  }
}
