package com.clavaris.organization.application.usecase.updateworkspacerole;

import java.util.UUID;

/**
 * ADR-0027 §3: rejected before persisting — {@link
 * com.clavaris.organization.domain.service.WorkspaceRoleHierarchy#wouldCreateCycle} found that
 * setting this {@code parentRoleId} would make the role its own (possibly indirect) ancestor.
 */
public final class WorkspaceRoleCycleException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public WorkspaceRoleCycleException(final UUID roleId, final UUID candidateParentId) {
    super(
        "Setting WorkspaceRole "
            + roleId
            + "'s parent to "
            + candidateParentId
            + " would create a cycle");
  }
}
