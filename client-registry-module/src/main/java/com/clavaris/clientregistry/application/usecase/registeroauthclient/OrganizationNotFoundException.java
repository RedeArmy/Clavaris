package com.clavaris.clientregistry.application.usecase.registeroauthclient;

import java.util.UUID;

/**
 * BR-ORG-02: an {@code OAuthClient} must belong to a real Organization — never a dangling
 * reference.
 */
public final class OrganizationNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public OrganizationNotFoundException(final UUID organizationId) {
    super("No Organization exists with id " + organizationId);
  }
}
