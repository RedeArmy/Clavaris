package com.clavaris.organization.application.usecase.addworkspacemember;

import java.util.UUID;

/**
 * ADR-0027: thrown when a {@code roleId} doesn't reference any {@code WorkspaceRole}, or references
 * one belonging to a different Organization than the Workspace being acted on — the latter is
 * deliberately reported identically to "doesn't exist" (never a distinct "wrong Organization"
 * message), same anti-enumeration posture {@code PlatformWorkspaceController}'s own Javadoc already
 * documents for a different lookup.
 */
public final class WorkspaceRoleNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public WorkspaceRoleNotFoundException(final UUID roleId) {
    super("No WorkspaceRole exists with id " + roleId);
  }
}
