package com.clavaris.identity.infrastructure.adapter.out.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.clavaris.common.application.port.CpuBoundVerificationGate;
import com.clavaris.identity.application.usecase.authenticatewithpassword.VerificationOverloadedException;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/**
 * ADR-0005 / test-strategy.md §2 (security-specific): same bar as {@link Argon2PasswordHasherTest}
 * — a real round trip through the actual hasher this verifier must interoperate with, not a mocked
 * encoder that could silently drift from what {@code Argon2PasswordHasher} actually produces.
 *
 * <p>TD-FUT-017: {@code alwaysAdmitting} is a real, minimal, immediate-pass-through {@link
 * CpuBoundVerificationGate} — not a mock — so the two tests below stay about the encoder's own
 * correctness; the gate's own concurrency/rejection behavior has its own dedicated test in {@code
 * common} (`SemaphoreCpuBoundVerificationGateTest`), and {@link
 * #throwsWhenTheConcurrencyGateIsSaturated()} below covers this class's own translation of a
 * rejected gate outcome into {@link VerificationOverloadedException}.
 */
class Argon2PasswordVerifierTest {

  private static final CpuBoundVerificationGate ALWAYS_ADMITTING =
      verification -> Optional.of(verification.getAsBoolean());
  private static final CpuBoundVerificationGate ALWAYS_REJECTING =
      (BooleanSupplier verification) -> Optional.empty();

  private final Argon2PasswordHasher hasher = new Argon2PasswordHasher();
  private final Argon2PasswordVerifier verifier = new Argon2PasswordVerifier(ALWAYS_ADMITTING);

  @Test
  void matchesTheCorrectPasswordAgainstAHashProducedByTheRealHasher() {
    String hash = hasher.hash("correct-horse-battery-staple");

    assertThat(verifier.matches("correct-horse-battery-staple", hash)).isTrue();
  }

  @Test
  void rejectsAWrongPassword() {
    String hash = hasher.hash("correct-horse-battery-staple");

    assertThat(verifier.matches("a-different-password", hash)).isFalse();
  }

  @Test
  void throwsWhenTheConcurrencyGateIsSaturated() {
    Argon2PasswordVerifier saturatedVerifier = new Argon2PasswordVerifier(ALWAYS_REJECTING);
    String hash = hasher.hash("correct-horse-battery-staple");

    assertThatThrownBy(() -> saturatedVerifier.matches("correct-horse-battery-staple", hash))
        .isInstanceOf(VerificationOverloadedException.class);
  }
}
