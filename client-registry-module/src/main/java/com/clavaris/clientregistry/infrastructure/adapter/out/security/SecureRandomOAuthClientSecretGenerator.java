package com.clavaris.clientregistry.infrastructure.adapter.out.security;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientSecretGenerator;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Component;

/**
 * Same rationale as {@code SecureRandomOrganizationClientSecretGenerator} — 256 bits of {@link
 * SecureRandom} entropy, URL-safe Base64 encoded (no padding). Same order of magnitude as the
 * RSA-2048 signing keys this credential ultimately guards access to, same reasoning {@code
 * RegisterOAuthClientService}'s own former inline generator (now replaced by this port) already
 * documented.
 */
@Component
class SecureRandomOAuthClientSecretGenerator implements OAuthClientSecretGenerator {

  private static final int SECRET_BYTES = 32;

  private final SecureRandom random = new SecureRandom();

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  /* package */ SecureRandomOAuthClientSecretGenerator() {
    // Intentionally empty — there's no state to initialise beyond the random field above.
  }

  @Override
  public String generate() {
    final byte[] bytes = new byte[SECRET_BYTES];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
