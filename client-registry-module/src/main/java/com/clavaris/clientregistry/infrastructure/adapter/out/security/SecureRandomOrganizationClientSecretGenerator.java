package com.clavaris.clientregistry.infrastructure.adapter.out.security;

import com.clavaris.clientregistry.application.usecase.createorganizationclient.OrganizationClientSecretGenerator;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Component;

/**
 * Same rationale as {@code SecureRandomPlatformClientSecretGenerator} — 256 bits of {@link
 * SecureRandom} entropy, URL-safe Base64 encoded (no padding).
 */
@Component
class SecureRandomOrganizationClientSecretGenerator implements OrganizationClientSecretGenerator {

  private static final int SECRET_BYTES = 32;

  private final SecureRandom random = new SecureRandom();

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  /* package */ SecureRandomOrganizationClientSecretGenerator() {
    // Intentionally empty — there's no state to initialise beyond the random field above.
  }

  @Override
  public String generate() {
    final byte[] bytes = new byte[SECRET_BYTES];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
