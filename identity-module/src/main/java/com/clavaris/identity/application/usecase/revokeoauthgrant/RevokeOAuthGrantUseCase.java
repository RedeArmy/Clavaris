package com.clavaris.identity.application.usecase.revokeoauthgrant;

@FunctionalInterface
public interface RevokeOAuthGrantUseCase {

  /**
   * @throws OAuthGrantNotFoundException if no matching grant belongs to {@code accountId}
   */
  void handle(RevokeOAuthGrantCommand command);
}
