package com.clavaris.identity.application.usecase.authenticatewithsocialprovider;

/**
 * BR-ORG-02: same organization-scoping discipline as {@code AuthenticateWithPasswordUseCase} — a
 * given {@code OAuthClient}'s hosted login screen authenticates only against that client's own
 * Organization's account pool, {@link AuthenticateWithSocialProviderCommand} carries {@code
 * organizationId} for exactly that reason.
 */
@FunctionalInterface
public interface AuthenticateWithSocialProviderUseCase {

  /**
   * @throws SocialLoginNotAllowedException if the Organization has not enabled this provider
   * @throws UnverifiedProviderEmailException if the provider did not report a verified email
   */
  AuthenticateWithSocialProviderResult handle(AuthenticateWithSocialProviderCommand command);
}
