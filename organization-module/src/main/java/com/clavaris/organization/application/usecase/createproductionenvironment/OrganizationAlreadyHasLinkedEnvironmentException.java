package com.clavaris.organization.application.usecase.createproductionenvironment;

import java.util.UUID;

/**
 * A {@code DEVELOPMENT} Organization can only ever have one paired production sibling — same "one
 * dev, one production" invariant {@link OrganizationNotDevelopmentException}'s own Javadoc names.
 * Prevents a second, orphaned production environment from ever being created against the same
 * development one.
 */
public final class OrganizationAlreadyHasLinkedEnvironmentException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public OrganizationAlreadyHasLinkedEnvironmentException(final UUID organizationId) {
    super("Organization " + organizationId + " already has a linked environment");
  }
}
