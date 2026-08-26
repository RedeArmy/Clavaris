package com.clavaris.organization.application.usecase.deleteorganization;

import java.util.UUID;

/**
 * Same rationale as this module's own equivalent in {@code setratelimitpolicyfororganization} —
 * never a dangling reference.
 */
public final class OrganizationNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public OrganizationNotFoundException(final UUID organizationId) {
    super("No Organization exists with id " + organizationId);
  }
}
