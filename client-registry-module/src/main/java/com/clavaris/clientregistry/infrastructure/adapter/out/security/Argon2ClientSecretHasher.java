package com.clavaris.clientregistry.infrastructure.adapter.out.security;

import com.clavaris.clientregistry.application.usecase.bootstrapplatformclient.ClientSecretHasher;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Same hashing discipline as {@code identity-module}'s {@code Argon2PasswordHasher} (ADR-0005) —
 * duplicated rather than shared across modules (see this module's pom.xml comment for why).
 * Arguably even higher-stakes here: CLAUDE.md §5 names the platform-tier credential the single
 * highest-value target in the whole system.
 */
@Component
class Argon2ClientSecretHasher implements ClientSecretHasher {

  private final Argon2PasswordEncoder encoder =
      Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  /* package */ Argon2ClientSecretHasher() {
    // Intentionally empty — there's no state to initialise beyond the encoder field above.
  }

  @Override
  public String hash(final String rawSecret) {
    return encoder.encode(rawSecret);
  }
}
