package com.clavaris.organization.application.usecase.addworkspacemember;

import java.util.UUID;

/**
 * Parked under {@code addworkspacemember} because that's this module's first use case to look up a
 * {@code Workspace} by id after creation — {@code changeworkspacememberrole}/{@code
 * removeworkspacemember}/{@code listworkspacemembers} are the other consumers, same "parked under
 * the first use case" precedent as this module's other exceptions.
 */
public final class WorkspaceNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public WorkspaceNotFoundException(final UUID workspaceId) {
    super("No Workspace exists with id " + workspaceId);
  }
}
