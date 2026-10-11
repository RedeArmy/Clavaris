package com.clavaris.organization.application.usecase.savesocialconnection;

import java.util.UUID;

/**
 * ADR-0022: an Organization's own OAuth app credentials belong to PRODUCTION Organizations. A
 * DEVELOPMENT sandbox always signs in through the shared Clavaris app, so credentials given for one
 * are refused rather than saved and never used.
 */
public final class CustomCredentialsNotAllowedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public CustomCredentialsNotAllowedException(final UUID organizationId) {
    super("Organization " + organizationId + " is not a PRODUCTION environment");
  }
}
