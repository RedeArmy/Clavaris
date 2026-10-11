package com.clavaris.organization.application.usecase.getsocialconnections;

import java.util.UUID;

/** The Organization whose connections were asked for does not exist. */
public final class OrganizationNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public OrganizationNotFoundException(final UUID organizationId) {
    super("Organization " + organizationId + " not found");
  }
}
