package com.clavaris.organization.infrastructure.adapter.out.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.common.application.port.SecurityMetricsRecorder;
import com.clavaris.common.infrastructure.adapter.out.crypto.AesGcmCipher;
import com.clavaris.common.infrastructure.adapter.out.crypto.SecretReencryptionStore;
import com.clavaris.common.infrastructure.adapter.out.crypto.StoredSecretReencryptor;
import com.clavaris.common.infrastructure.adapter.out.crypto.StoredSecretReencryptor.Result;
import com.clavaris.organization.domain.model.SocialProvider;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * TD-SEC-089 against a real schema: a value written under the old key, and one written before the
 * envelope format, end up readable with the new key alone, and a value that cannot be read does not
 * stop the rest.
 */
@SpringBootTest(classes = SocialCredentialReencryptionTest.TestConfig.class)
@Testcontainers
class SocialCredentialReencryptionTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  private static final String OLD_KEY = newKey();
  private static final String NEW_KEY = newKey();

  @Autowired private JdbcTemplate jdbc;

  private UUID organization(final String name) {
    final UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into organizations (id, name, owner_platform_account_id) values (?, ?, ?)",
        id,
        name,
        UUID.randomUUID());
    return id;
  }

  private void store(final UUID organizationId, final String provider, final String value) {
    jdbc.update(
        "insert into organization_social_credentials"
            + " (organization_id, provider, client_id, client_secret_encrypted)"
            + " values (?, ?, 'client', ?)",
        organizationId,
        provider,
        value);
  }

  private String stored(final UUID organizationId, final String provider) {
    return jdbc.queryForObject(
        "select client_secret_encrypted from organization_social_credentials"
            + " where organization_id = ? and provider = ?",
        String.class,
        organizationId,
        provider);
  }

  @Test
  void everyReadableSecretEndsUpUnderTheNewKeyAlone() {
    final UUID first = organization("First Co");
    final UUID second = organization("Second Co");
    final UUID third = organization("Third Co");
    final AesGcmOrganizationSocialCredentialCipher old =
        new AesGcmOrganizationSocialCredentialCipher(
            OLD_KEY, "1", "", noBean(SecurityMetricsRecorder.class));
    store(first, "GOOGLE", old.encrypt(first, SocialProvider.GOOGLE, "SECRETONE"));
    store(second, "GITHUB", new AesGcmCipher(OLD_KEY).encrypt("SECRETTWO"));
    store(third, "GOOGLE", "v1.1.not-a-real-ciphertext");
    final AesGcmOrganizationSocialCredentialCipher rotated =
        new AesGcmOrganizationSocialCredentialCipher(
            NEW_KEY, "2", "1:" + OLD_KEY, noBean(SecurityMetricsRecorder.class));

    final List<Result> results = sweep(rotated);

    assertThat(results).containsExactly(new Result(2, 1));
    final AesGcmOrganizationSocialCredentialCipher onlyNew =
        new AesGcmOrganizationSocialCredentialCipher(
            NEW_KEY, "2", "", noBean(SecurityMetricsRecorder.class));
    assertThat(onlyNew.decrypt(first, SocialProvider.GOOGLE, stored(first, "GOOGLE")))
        .isEqualTo("SECRETONE");
    assertThat(onlyNew.decrypt(second, SocialProvider.GITHUB, stored(second, "GITHUB")))
        .isEqualTo("SECRETTWO");
    assertThat(stored(third, "GOOGLE")).isEqualTo("v1.1.not-a-real-ciphertext");
    assertThat(sweep(rotated)).containsExactly(new Result(0, 1));
  }

  // The sweep as the job runs it, one batch row at a time to exercise the paging.
  private List<Result> sweep(final SecretReencryptionStore store) {
    final StoredSecretReencryptor reencryptor =
        new StoredSecretReencryptor(jdbc, (name, tags) -> {}, 1);
    return store.targets().stream().map(t -> reencryptor.run(store.envelope(), t)).toList();
  }

  private static String newKey() {
    final byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getEncoder().encodeToString(bytes);
  }

  private static <T> ObjectProvider<T> noBean(final Class<T> type) {
    return new DefaultListableBeanFactory().getBeanProvider(type);
  }

  @Configuration
  @EnableAutoConfiguration
  static class TestConfig {}
}
