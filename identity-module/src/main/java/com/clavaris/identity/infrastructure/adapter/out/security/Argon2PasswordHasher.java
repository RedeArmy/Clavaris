package com.clavaris.identity.infrastructure.adapter.out.security;

import com.clavaris.identity.application.usecase.registeraccount.PasswordHasher;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * ADR-0005: Argon2id via Spring Security's {@link Argon2PasswordEncoder}. Parameters use the
 * factory's own current-recommendation defaults ({@code
 * Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8}) rather than hand-picked numbers — Spring
 * Security's own team tracks OWASP guidance on cost-factor recommendations over time; re-deriving
 * that judgement here would be the kind of hand-rolled security decision CLAUDE.md §1 explicitly
 * avoids ("never reinventing cryptography... on top of a vetted framework").
 */
@Component
class Argon2PasswordHasher implements PasswordHasher {

  private final Argon2PasswordEncoder encoder =
      Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();

  // This constructor is written out explicitly, rather than left to the compiler's implicit
  // default, only because one PMD rule (requiring at least one declared constructor) and another
  // PMD rule (rejecting a redundant no-op one) want opposite things for a class that genuinely
  // needs nothing beyond a no-op constructor — the suppression below resolves that conflict in
  // favour of the explicit form. Instances are created only by Spring's own component scan or
  // directly by tests living in this same package — PasswordHasher, the port, is the only type
  // any caller outside this package should ever depend on.
  @SuppressWarnings("PMD.UnnecessaryConstructor")
  /* package */ Argon2PasswordHasher() {
    // Intentionally empty — there's no state to initialise beyond the encoder field above.
  }

  @Override
  public String hash(final String rawPassword) {
    return encoder.encode(rawPassword);
  }
}
