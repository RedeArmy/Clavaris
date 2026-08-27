package com.clavaris.organization.application.usecase.removeworkspacemember;

import java.util.UUID;

/**
 * BR-WS-01's replacement invariant — same rationale as this module's sibling in {@code
 * changeworkspacememberrole}: removing the last ADMIN would leave the workspace with none.
 */
public final class CannotRemoveLastAdminException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public CannotRemoveLastAdminException(final UUID workspaceId) {
    super("Workspace " + workspaceId + " must retain at least one ADMIN member");
  }
}
