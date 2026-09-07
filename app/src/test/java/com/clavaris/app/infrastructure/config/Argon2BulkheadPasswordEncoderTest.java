package com.clavaris.app.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.clavaris.common.application.port.CpuBoundVerificationGate;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;

/**
 * TD-FUT-017. Unit-level, not a live {@code /oauth2/token} round trip — that end-to-end proof (does
 * {@code ClientSecretAuthenticationProvider} really propagate this exception all the way to a
 * spec-shaped OAuth2 JSON error response?) was verified by decompiling the actual resolved {@code
 * spring-security-oauth2-authorization-server:7.1.1} jar via {@code javap} instead (see this
 * class's own Javadoc for the exact bytecode-level finding), not by a Testcontainers-backed
 * integration test in this pass — a named, deliberate scope limit, not an oversight. What this test
 * verifies directly: the decorator's own two outcomes never get conflated with each other or with a
 * genuine wrong-secret result.
 */
class Argon2BulkheadPasswordEncoderTest {

  private static final CpuBoundVerificationGate ALWAYS_ADMITTING =
      verification -> Optional.of(verification.getAsBoolean());
  private static final CpuBoundVerificationGate ALWAYS_REJECTING = verification -> Optional.empty();

  @Test
  void matchesTheCorrectSecretAgainstAHashProducedByItsOwnEncode() {
    Argon2BulkheadPasswordEncoder encoder = new Argon2BulkheadPasswordEncoder(ALWAYS_ADMITTING);
    String hash = encoder.encode("a-real-client-secret");

    assertThat(encoder.matches("a-real-client-secret", hash)).isTrue();
  }

  @Test
  void rejectsAWrongSecretWithoutThrowing() {
    Argon2BulkheadPasswordEncoder encoder = new Argon2BulkheadPasswordEncoder(ALWAYS_ADMITTING);
    String hash = encoder.encode("a-real-client-secret");

    assertThat(encoder.matches("a-different-secret", hash)).isFalse();
  }

  @Test
  void throwsAServerErrorOAuth2ExceptionWhenTheGateIsSaturated() {
    Argon2BulkheadPasswordEncoder encoder = new Argon2BulkheadPasswordEncoder(ALWAYS_REJECTING);
    String hash = encoder.encode("a-real-client-secret");

    assertThatThrownBy(() -> encoder.matches("a-real-client-secret", hash))
        .isInstanceOf(OAuth2AuthenticationException.class)
        .satisfies(
            thrown ->
                assertThat(((OAuth2AuthenticationException) thrown).getError().getErrorCode())
                    .isEqualTo(OAuth2ErrorCodes.SERVER_ERROR));
  }
}
