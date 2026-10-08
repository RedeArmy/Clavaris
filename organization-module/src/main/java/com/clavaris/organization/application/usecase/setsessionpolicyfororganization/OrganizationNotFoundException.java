package com.clavaris.organization.application.usecase.setsessionpolicyfororganization;

import java.util.UUID;

/**
 * Same rationale as every sibling policy use case's own equivalent — never a dangling reference.
 */
public final class OrganizationNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public OrganizationNotFoundException(final UUID organizationId) {
    super("No Organization exists with id " + organizationId);
  }
}
