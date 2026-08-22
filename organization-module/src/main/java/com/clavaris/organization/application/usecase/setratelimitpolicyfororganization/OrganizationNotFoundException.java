package com.clavaris.organization.application.usecase.setratelimitpolicyfororganization;

import java.util.UUID;

/** Same rationale as client-registry-module's own equivalent — never a dangling reference. */
public final class OrganizationNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public OrganizationNotFoundException(final UUID organizationId) {
    super("No Organization exists with id " + organizationId);
  }
}
