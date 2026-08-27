package com.clavaris.organization.application.usecase.changeworkspacememberrole;

import java.util.UUID;

/**
 * BR-WS-01's replacement invariant (ADR-0010 §3 addendum, 2026-08-27): a {@code Workspace} must
 * always retain at least one {@code ADMIN} member — enforced here, at the application layer, not
 * left to a database constraint alone, same posture the original OWNER invariant already
 * established.
 */
public final class CannotDemoteLastAdminException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public CannotDemoteLastAdminException(final UUID workspaceId) {
    super("Workspace " + workspaceId + " must retain at least one ADMIN member");
  }
}
