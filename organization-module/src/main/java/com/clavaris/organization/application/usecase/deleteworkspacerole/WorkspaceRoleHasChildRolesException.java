package com.clavaris.organization.application.usecase.deleteworkspacerole;

import java.util.UUID;

/**
 * ADR-0027 §3: a role still referenced as another role's {@code parentRoleId} cannot be deleted —
 * {@code workspace_roles.parent_role_id} has no {@code ON DELETE} action (deliberately, see that
 * migration's own comment), so without this guard the delete would instead fail with a raw Postgres
 * foreign-key violation. The consumer must re-parent or clear every child role's {@code
 * parentRoleId} first, the same explicit, no-implicit-cascade posture {@link
 * WorkspaceRoleStillAssignedException} already establishes for membership reassignment.
 */
public final class WorkspaceRoleHasChildRolesException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public WorkspaceRoleHasChildRolesException(final UUID roleId) {
    super(
        "WorkspaceRole "
            + roleId
            + " is still the parent of at least one other role — re-parent or clear them first");
  }
}
