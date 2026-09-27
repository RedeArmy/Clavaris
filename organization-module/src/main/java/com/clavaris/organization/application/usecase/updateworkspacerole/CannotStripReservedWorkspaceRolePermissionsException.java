package com.clavaris.organization.application.usecase.updateworkspacerole;

import java.util.UUID;

/**
 * ADR-0027 §2: this Organization's one {@code reserved} role can never be stripped of {@link
 * com.clavaris.organization.domain.model.ReservedWorkspacePermissions#ALL} — renaming it or
 * re-parenting it is allowed (see {@code WorkspaceRolesController}'s own Javadoc), only its
 * permission set is protected. Raised here, before {@link
 * com.clavaris.organization.domain.model.WorkspaceRole#withPermissions} ever runs, so this surfaces
 * as a clean, typed rejection both callers (the REST admin API and the dashboard) can map to a
 * proper 4xx — without this guard, the domain's own identical check further down would instead
 * throw a bare {@code IllegalArgumentException}, an unhandled 500 for a call any admin-API consumer
 * could make.
 */
public final class CannotStripReservedWorkspaceRolePermissionsException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public CannotStripReservedWorkspaceRolePermissionsException(final UUID roleId) {
    super(
        "WorkspaceRole "
            + roleId
            + " is reserved and can never be stripped of its reserved permissions");
  }
}
