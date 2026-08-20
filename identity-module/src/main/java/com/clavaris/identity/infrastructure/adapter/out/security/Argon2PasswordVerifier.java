package com.clavaris.identity.infrastructure.adapter.out.security;

import com.clavaris.identity.application.usecase.authenticatewithpassword.PasswordVerifier;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * ADR-0005: the read (verify) side of the same Argon2id encoder {@link Argon2PasswordHasher} wraps
 * for the write (hash) side — see {@code PasswordVerifier}'s own Javadoc for why this is a separate
 * port/adapter pair rather than one class doing both.
 */
@Component
class Argon2PasswordVerifier implements PasswordVerifier {

  private final Argon2PasswordEncoder encoder =
      Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();

  // Same rationale as Argon2PasswordHasher's own explicit no-op constructor — see its comment.
  @SuppressWarnings("PMD.UnnecessaryConstructor")
  /* package */ Argon2PasswordVerifier() {
    // Intentionally empty — there's no state to initialise beyond the encoder field above.
  }

  @Override
  public boolean matches(final String rawPassword, final String passwordHash) {
    return encoder.matches(rawPassword, passwordHash);
  }
}
