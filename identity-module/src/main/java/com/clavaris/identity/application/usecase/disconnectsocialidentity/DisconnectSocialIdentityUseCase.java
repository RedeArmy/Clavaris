package com.clavaris.identity.application.usecase.disconnectsocialidentity;

@FunctionalInterface
public interface DisconnectSocialIdentityUseCase {

  /**
   * @throws LastSignInMethodException removing it would leave the Account unable to sign in
   * @throws SocialIdentityNotLinkedException the provider is not linked to this Account
   */
  void handle(DisconnectSocialIdentityCommand command);
}
