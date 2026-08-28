package com.clavaris.organization.application.usecase.createworkspace;

import java.util.UUID;

/**
 * Same rationale as this module's own equivalent in {@code deleteorganization}/{@code
 * setratelimitpolicyfororganization} — never a dangling reference. A {@code Workspace} can never be
 * created inside an Organization that doesn't exist.
 */
public final class OrganizationNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public OrganizationNotFoundException(final UUID organizationId) {
    super("No Organization exists with id " + organizationId);
  }
}
