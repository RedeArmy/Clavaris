package com.clavaris.organization.application.usecase.removeworkspacemember;

import java.util.UUID;

/**
 * BR-WS-01's replacement invariant, generalized by ADR-0027 — same rationale, and same "class name
 * kept for continuity" note, as this module's sibling in {@code changeworkspacememberrole}.
 */
public final class CannotRemoveLastAdminException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public CannotRemoveLastAdminException(final UUID workspaceId) {
    super(
        "Workspace "
            + workspaceId
            + " must retain at least one member holding clavaris:workspace:manage_members");
  }
}
