package com.clavaris.organization.application.usecase.updateorganizationprofile;

import java.util.UUID;

/**
 * Thrown when the Organization does not exist <em>or belongs to someone else</em>: the two are the
 * same answer on purpose, so the edit dialog cannot be used to find out which Organization ids
 * exist. Same rationale as this module's sibling in {@code setratelimitpolicyfororganization}.
 */
public final class OrganizationNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public OrganizationNotFoundException(final UUID organizationId) {
    super("No Organization exists with id " + organizationId);
  }
}
