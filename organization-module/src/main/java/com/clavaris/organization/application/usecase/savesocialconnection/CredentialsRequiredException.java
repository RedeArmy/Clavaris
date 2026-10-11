package com.clavaris.organization.application.usecase.savesocialconnection;

import com.clavaris.organization.domain.model.SocialProvider;
import java.util.UUID;

/**
 * A PRODUCTION Organization signs in through its own OAuth app, never the shared Clavaris one, so
 * it cannot enable a provider it has no credentials for.
 */
public final class CredentialsRequiredException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public CredentialsRequiredException(final UUID organizationId, final SocialProvider provider) {
    super(
        "Organization "
            + organizationId
            + " needs its own "
            + provider
            + " credentials before the provider can be enabled");
  }
}
