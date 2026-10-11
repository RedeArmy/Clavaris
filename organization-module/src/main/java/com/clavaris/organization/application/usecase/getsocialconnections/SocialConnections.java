package com.clavaris.organization.application.usecase.getsocialconnections;

import com.clavaris.organization.domain.model.OrganizationEnvironment;
import java.util.List;

/**
 * An Organization's connections, with the environment that decides what it may do with them: only a
 * PRODUCTION Organization has credentials of its own; a DEVELOPMENT one uses the shared app.
 */
public record SocialConnections(
    OrganizationEnvironment environment, List<SocialConnection> connections) {

  /** Whether the Organization is PRODUCTION, and so signs in only through its own credentials. */
  public boolean production() {
    return environment == OrganizationEnvironment.PRODUCTION;
  }
}
