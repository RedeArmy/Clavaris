package com.clavaris.clientregistry.infrastructure.adapter.out.security;

import com.clavaris.clientregistry.application.usecase.rotateplatformclientsecret.PlatformClientSecretGenerator;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Component;

/**
 * TD-SEC-018: 256 bits of {@link SecureRandom} entropy, URL-safe Base64 encoded (no padding) — same
 * entropy class this codebase already trusts for {@code RefreshTokenSecret}, applied here to the
 * single highest-value credential in the system. A machine credential, never a human-memorable one,
 * so there's no password-policy tradeoff to make the way {@code PasswordPolicy} makes for {@code
 * Account} passwords.
 */
@Component
class SecureRandomPlatformClientSecretGenerator implements PlatformClientSecretGenerator {

  private static final int SECRET_BYTES = 32;

  private final SecureRandom random = new SecureRandom();

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  /* package */ SecureRandomPlatformClientSecretGenerator() {
    // Intentionally empty — there's no state to initialise beyond the random field above.
  }

  @Override
  public String generate() {
    final byte[] bytes = new byte[SECRET_BYTES];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
