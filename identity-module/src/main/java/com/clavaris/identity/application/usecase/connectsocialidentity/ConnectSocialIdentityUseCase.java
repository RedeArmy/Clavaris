package com.clavaris.identity.application.usecase.connectsocialidentity;

@FunctionalInterface
public interface ConnectSocialIdentityUseCase {

  /**
   * @throws SocialConnectionNotAllowedException the Organization does not allow this provider
   * @throws SocialIdentityAlreadyLinkedException this provider is already linked to this Account,
   *     or that provider identity belongs to another Account in the Organization
   */
  void handle(ConnectSocialIdentityCommand command);
}
