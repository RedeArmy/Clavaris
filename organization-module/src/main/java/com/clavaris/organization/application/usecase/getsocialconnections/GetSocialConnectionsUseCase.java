package com.clavaris.organization.application.usecase.getsocialconnections;

import java.util.UUID;

/** Reads every social connection of an Organization, one per supported provider. */
@FunctionalInterface
public interface GetSocialConnectionsUseCase {

  /**
   * @throws OrganizationNotFoundException when the Organization does not exist
   */
  SocialConnections handle(UUID organizationId);
}
