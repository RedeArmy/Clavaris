package com.clavaris.organization.application.usecase.deleteworkspacerole;

import java.util.UUID;

/** ADR-0027 §1/§2: the one system-seeded bootstrap role per Organization can never be deleted. */
public final class CannotDeleteReservedWorkspaceRoleException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public CannotDeleteReservedWorkspaceRoleException(final UUID roleId) {
    super("WorkspaceRole " + roleId + " is reserved and cannot be deleted");
  }
}
