package com.clavaris.identity.infrastructure.adapter.out.security;

import com.clavaris.common.application.port.CpuBoundVerificationGate;
import com.clavaris.identity.application.usecase.authenticatewithpassword.PasswordVerifier;
import com.clavaris.identity.application.usecase.authenticatewithpassword.VerificationOverloadedException;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * ADR-0005: the read (verify) side of the same Argon2id encoder {@link Argon2PasswordHasher} wraps
 * for the write (hash) side — see {@code PasswordVerifier}'s own Javadoc for why this is a separate
 * port/adapter pair rather than one class doing both.
 *
 * <p>TD-FUT-017: {@code matches} runs through the shared {@link CpuBoundVerificationGate} rather
 * than calling the encoder directly — this is the exact call site `load-testing/README.md` §4
 * isolated as the concurrency ceiling. Never returns {@code false} on a rejected/overloaded
 * outcome: that would be indistinguishable from a genuinely wrong password to every caller up the
 * stack (the controller, BR-ID-06's own lockout counters), so a saturated gate throws instead — see
 * {@link VerificationOverloadedException}'s own Javadoc.
 */
@Component
class Argon2PasswordVerifier implements PasswordVerifier {

  private final Argon2PasswordEncoder encoder =
      Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
  private final CpuBoundVerificationGate concurrencyGate;

  /* package */ Argon2PasswordVerifier(final CpuBoundVerificationGate concurrencyGate) {
    this.concurrencyGate = concurrencyGate;
  }

  @Override
  public boolean matches(final String rawPassword, final String passwordHash) {
    return concurrencyGate
        .runBounded(() -> encoder.matches(rawPassword, passwordHash))
        .orElseThrow(VerificationOverloadedException::new);
  }
}
