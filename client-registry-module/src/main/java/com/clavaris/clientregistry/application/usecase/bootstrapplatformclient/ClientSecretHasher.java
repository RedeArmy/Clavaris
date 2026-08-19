package com.clavaris.clientregistry.application.usecase.bootstrapplatformclient;

/**
 * Outbound port — implemented by {@code
 * infrastructure/adapter/out/security/Argon2ClientSecretHasher}.
 */
@FunctionalInterface
public interface ClientSecretHasher {

  String hash(String rawSecret);
}
