package com.clavaris.app.infrastructure.adapter.out.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.clavaris.clientregistry.domain.service.HighEntropyClientSecret;
import com.clavaris.common.application.port.CpuBoundVerificationGate;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;

class ClientSecretPasswordEncoderTest {

  // 43 characters: a generated 256-bit secret in unpadded URL-safe Base64.
  private static final String GENERATED = "k3Jx0dQb9vYwPz2mNfRt7LaUcHsE5oGiTnBqXyVhZ4A";
  private static final String CHOSEN_BY_AN_OPERATOR = "lt-platform-secret-chosen-by-the-operator";

  private final AtomicInteger gateUses = new AtomicInteger();
  private final CpuBoundVerificationGate countingGate =
      verification -> {
        gateUses.incrementAndGet();
        return Optional.of(verification.getAsBoolean());
      };
  private final ClientSecretPasswordEncoder encoder =
      new ClientSecretPasswordEncoder(new Argon2BulkheadPasswordEncoder(countingGate));

  @Test
  void aGeneratedSecretIsCheckedAgainstItsFastHash() {
    final String stored = HighEntropyClientSecret.hash(GENERATED);

    assertThat(encoder.matches(GENERATED, stored)).isTrue();
    assertThat(encoder.matches(GENERATED + "x", stored)).isFalse();
  }

  // The point of the scheme: the common request must not queue behind, or be rejected by, the
  // bounded pool that protects the CPU from Argon2.
  @Test
  void aFastHashNeverTouchesTheArgon2Bulkhead() {
    final String stored = HighEntropyClientSecret.hash(GENERATED);

    encoder.matches(GENERATED, stored);
    encoder.matches("wrong-" + GENERATED, stored);

    assertThat(gateUses).hasValue(0);
  }

  @Test
  void aFastHashIsStillVerifiedWhenTheBulkheadIsSaturated() {
    final ClientSecretPasswordEncoder saturated =
        new ClientSecretPasswordEncoder(
            new Argon2BulkheadPasswordEncoder(verification -> Optional.empty()));

    assertThat(saturated.matches(GENERATED, HighEntropyClientSecret.hash(GENERATED))).isTrue();
  }

  @Test
  void anArgon2HashFromBeforeTheFastSchemeStillAuthenticates() {
    final String legacy = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode(GENERATED);

    assertThat(encoder.matches(GENERATED, legacy)).isTrue();
    assertThat(encoder.matches("not-the-secret-" + GENERATED, legacy)).isFalse();
  }

  @Test
  void theOperatorChosenBootstrapSecretStaysOnArgon2AndThroughTheBulkhead() {
    final String stored = encoder.encode(CHOSEN_BY_AN_OPERATOR);

    assertThat(stored).startsWith("$argon2");
    assertThat(encoder.matches(CHOSEN_BY_AN_OPERATOR, stored)).isTrue();
    assertThat(gateUses).hasValue(1);
  }

  @Test
  void anArgon2CheckIsRejectedLikeBeforeWhenTheBulkheadIsSaturated() {
    final ClientSecretPasswordEncoder saturated =
        new ClientSecretPasswordEncoder(
            new Argon2BulkheadPasswordEncoder(verification -> Optional.empty()));
    final String legacy = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode(GENERATED);

    assertThatThrownBy(() -> saturated.matches(GENERATED, legacy))
        .isInstanceOf(OAuth2AuthenticationException.class);
  }

  @Test
  void aStoredValueThatIsNeitherSchemeNeverMatches() {
    assertThat(encoder.matches(GENERATED, GENERATED)).isFalse();
    assertThat(encoder.matches(GENERATED, "{bcrypt}$2a$10$abcdefghijklmnopqrstuv")).isFalse();
    assertThat(encoder.matches(GENERATED, "{noop}" + GENERATED)).isFalse();
    assertThat(encoder.matches(GENERATED, "")).isFalse();
    assertThat(encoder.matches(GENERATED, null)).isFalse();
  }

  // Spring Authorization Server calls upgradeEncoding after a successful check and, on true, tries
  // to RegisteredClientRepository#save the re-encoded secret. These repositories do not support
  // that write, so answering true would make every legacy client fail to authenticate.
  @Test
  void neverAsksTheServerToRewriteAStoredSecret() {
    assertThat(encoder.upgradeEncoding(HighEntropyClientSecret.hash(GENERATED))).isFalse();
    assertThat(
            encoder.upgradeEncoding(
                Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode(GENERATED)))
        .isFalse();
  }
}
