package com.clavaris.organization.application.usecase.changeworkspacememberrole;

import java.util.UUID;

/**
 * BR-WS-01's replacement invariant, generalized by ADR-0027 (§2) from the original ADR-0010 §3
 * addendum wording: a {@code Workspace} must always retain at least one membership whose effective
 * permissions include {@code clavaris:workspace:manage_members} — enforced here, at the application
 * layer, not left to a database constraint alone. Class name kept for continuity (fewer call sites
 * to touch); the invariant it represents is the generalized one, not the original fixed-{@code
 * ADMIN} one.
 */
public final class CannotDemoteLastAdminException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public CannotDemoteLastAdminException(final UUID workspaceId) {
    super(
        "Workspace "
            + workspaceId
            + " must retain at least one member holding clavaris:workspace:manage_members");
  }
}
