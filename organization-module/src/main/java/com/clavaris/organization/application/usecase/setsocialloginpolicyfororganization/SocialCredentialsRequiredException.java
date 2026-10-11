package com.clavaris.organization.application.usecase.setsocialloginpolicyfororganization;

import java.util.UUID;

/**
 * ADR-0022, as amended: a PRODUCTION Organization signs in only through its own OAuth app, never
 * the shared Clavaris one, so a provider it has no credentials for cannot be enabled.
 */
public final class SocialCredentialsRequiredException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public SocialCredentialsRequiredException(final UUID organizationId, final String provider) {
    super(
        "Organization "
            + organizationId
            + " needs its own "
            + provider
            + " credentials before the provider can be enabled");
  }
}
