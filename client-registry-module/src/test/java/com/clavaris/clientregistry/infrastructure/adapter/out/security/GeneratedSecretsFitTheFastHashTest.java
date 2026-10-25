package com.clavaris.clientregistry.infrastructure.adapter.out.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.clientregistry.domain.service.HighEntropyClientSecret;
import org.junit.jupiter.api.Test;

/**
 * The length floor in {@link HighEntropyClientSecret} exists to refuse a person-chosen secret. It
 * must never refuse what the real generators produce, or registering a client would start failing
 * in production while every mocked unit test stayed green: this checks the real generators against
 * the real floor.
 */
class GeneratedSecretsFitTheFastHashTest {

  @Test
  void everyRealSecretGeneratorProducesASecretTheFastHashAccepts() {
    for (int i = 0; i < 50; i++) {
      assertThat(
              HighEntropyClientSecret.hash(new SecureRandomOAuthClientSecretGenerator().generate()))
          .startsWith(HighEntropyClientSecret.PREFIX);
      assertThat(
              HighEntropyClientSecret.hash(
                  new SecureRandomOrganizationClientSecretGenerator().generate()))
          .startsWith(HighEntropyClientSecret.PREFIX);
      assertThat(
              HighEntropyClientSecret.hash(
                  new SecureRandomPlatformClientSecretGenerator().generate()))
          .startsWith(HighEntropyClientSecret.PREFIX);
    }
  }

  @Test
  void twoGeneratedSecretsNeverShareAHash() {
    final SecureRandomOAuthClientSecretGenerator generator =
        new SecureRandomOAuthClientSecretGenerator();

    assertThat(HighEntropyClientSecret.hash(generator.generate()))
        .isNotEqualTo(HighEntropyClientSecret.hash(generator.generate()));
  }
}
