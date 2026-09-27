package com.clavaris.organization.application.usecase.deleteworkspacerole;

import java.util.UUID;

/**
 * ADR-0027 §5: a role still referenced by at least one {@code WorkspaceMembership} cannot be
 * deleted — the consumer must reassign or unassign every affected member first, an explicit,
 * no-implicit-cascade action, not something this delete performs on their behalf.
 */
public final class WorkspaceRoleStillAssignedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public WorkspaceRoleStillAssignedException(final UUID roleId) {
    super(
        "WorkspaceRole "
            + roleId
            + " is still assigned to at least one member — reassign or unassign them first");
  }
}
