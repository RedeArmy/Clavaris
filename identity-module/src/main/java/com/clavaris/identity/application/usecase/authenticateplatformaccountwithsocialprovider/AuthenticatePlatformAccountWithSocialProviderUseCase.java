package com.clavaris.identity.application.usecase.authenticateplatformaccountwithsocialprovider;

/**
 * ADR-0020 Decision 2: Sign In/Sign Up for Clavaris itself is Google/GitHub/email only, all three
 * permanently coexisting — unlike the tenant-tier sibling, there is no per-Organization policy to
 * re-check here at all (Clavaris's own login is not tenant-configurable).
 */
@FunctionalInterface
public interface AuthenticatePlatformAccountWithSocialProviderUseCase {

  /**
   * @throws UnverifiedPlatformProviderEmailException if the provider did not report a verified
   *     email
   */
  AuthenticatePlatformAccountWithSocialProviderResult handle(
      AuthenticatePlatformAccountWithSocialProviderCommand command);
}
