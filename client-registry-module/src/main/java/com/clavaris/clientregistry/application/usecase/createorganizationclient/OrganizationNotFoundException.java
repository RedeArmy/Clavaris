package com.clavaris.clientregistry.application.usecase.createorganizationclient;

import java.util.UUID;

/** Same rationale as this module's sibling in {@code registeroauthclient}. */
public final class OrganizationNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public OrganizationNotFoundException(final UUID organizationId) {
    super("No Organization exists with id " + organizationId);
  }
}
