package com.clavaris.identity.application.usecase.registeraccount;

/**
 * Outbound port — implemented by {@code infrastructure/adapter/out/security/Argon2PasswordHasher}
 * (ADR-0005). The application layer never knows which algorithm; that's exactly the point of this
 * being a port instead of a direct dependency on Spring Security's encoder type.
 */
@FunctionalInterface
public interface PasswordHasher {

  String hash(String rawPassword);
}
