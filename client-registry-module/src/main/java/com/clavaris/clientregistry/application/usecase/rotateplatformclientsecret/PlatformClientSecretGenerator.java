package com.clavaris.clientregistry.application.usecase.rotateplatformclientsecret;

/**
 * Outbound port — generates a fresh, random raw secret for a {@code PlatformClient}. Unlike a
 * password, nobody chooses this value; it's a machine credential, so this port exists instead of
 * accepting caller input the way {@code RegisterAccountCommand}'s own password field does.
 * Implemented by {@code
 * infrastructure.adapter.out.security.SecureRandomPlatformClientSecretGenerator}.
 */
@FunctionalInterface
public interface PlatformClientSecretGenerator {

  String generate();
}
