package com.clavaris.organization.application.usecase.changeworkspacememberrole;

import java.util.UUID;

/** Parked here as this package's first use case — {@code removeworkspacemember} is the other. */
public final class WorkspaceMembershipNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public WorkspaceMembershipNotFoundException(final UUID workspaceId, final UUID accountId) {
    super("No membership exists for account " + accountId + " in workspace " + workspaceId);
  }
}
