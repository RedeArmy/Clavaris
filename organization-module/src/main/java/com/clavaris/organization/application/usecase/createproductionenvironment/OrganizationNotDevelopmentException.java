package com.clavaris.organization.application.usecase.createproductionenvironment;

import java.util.UUID;

/**
 * A production environment can only ever be created from a {@code DEVELOPMENT} Organization — same
 * "one dev, one production, per application" invariant Clerk's own Instances model enforces.
 * Creating one from an already-{@code PRODUCTION} Organization (or from one that is itself already
 * a production sibling) has no meaningful semantics.
 */
public final class OrganizationNotDevelopmentException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public OrganizationNotDevelopmentException(final UUID organizationId) {
    super("Organization " + organizationId + " is not a DEVELOPMENT environment");
  }
}
