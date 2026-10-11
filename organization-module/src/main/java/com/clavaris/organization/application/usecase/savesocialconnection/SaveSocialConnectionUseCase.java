package com.clavaris.organization.application.usecase.savesocialconnection;

/**
 * Saves one social connection of an Organization in one step: whether it is enabled for sign-up and
 * sign-in, the Organization's own OAuth app credentials, and the connection's settings.
 */
@FunctionalInterface
public interface SaveSocialConnectionUseCase {

  /**
   * @throws OrganizationNotFoundException when the Organization does not exist
   * @throws CustomCredentialsNotAllowedException when credentials are given for a DEVELOPMENT
   *     Organization, which always uses the shared Clavaris app
   * @throws IncompleteCredentialsException when only one of client id and secret is given and there
   *     are none saved to complete it with
   * @throws CredentialsRequiredException when a PRODUCTION Organization enables a provider it has
   *     no credentials for
   */
  void handle(SaveSocialConnectionCommand command);
}
