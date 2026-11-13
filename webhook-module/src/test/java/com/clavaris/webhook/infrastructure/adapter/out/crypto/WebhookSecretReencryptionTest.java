package com.clavaris.webhook.infrastructure.adapter.out.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.common.application.port.SecurityMetricsRecorder;
import com.clavaris.common.infrastructure.adapter.out.crypto.AesGcmCipher;
import com.clavaris.common.infrastructure.adapter.out.crypto.SecretReencryptionStore;
import com.clavaris.common.infrastructure.adapter.out.crypto.StoredSecretReencryptor;
import com.clavaris.common.infrastructure.adapter.out.crypto.StoredSecretReencryptor.Result;
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
@SpringBootTest(classes = WebhookSecretReencryptionTest.TestConfig.class)
@Testcontainers
class WebhookSecretReencryptionTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  private static final String OLD_KEY = newKey();
  private static final String NEW_KEY = newKey();

  @Autowired private JdbcTemplate jdbc;

  private void store(final UUID id, final String current, final String previous) {
    jdbc.update(
        "insert into webhook_endpoints (id, organization_id, url, subscribed_event_types,"
            + " current_secret_encrypted, previous_secret_encrypted)"
            + " values (?, ?, 'https://example.com/hook', '[\"x\"]', ?, ?)",
        id,
        UUID.randomUUID(),
        current,
        previous);
  }

  private String column(final UUID id, final String column) {
    return jdbc.queryForObject(
        "select " + column + " from webhook_endpoints where id = ?", String.class, id);
  }

  @Test
  void bothSecretsOfAnEndpointEndUpUnderTheNewKeyAlone() {
    final UUID rotating = UUID.randomUUID();
    final UUID legacy = UUID.randomUUID();
    final UUID damaged = UUID.randomUUID();
    final AesGcmWebhookSigningSecretCipher old =
        new AesGcmWebhookSigningSecretCipher(
            OLD_KEY, "1", "", true, noBean(SecurityMetricsRecorder.class));
    store(rotating, old.encrypt(rotating, "CURRENT"), old.encrypt(rotating, "PREVIOUS"));
    store(legacy, new AesGcmCipher(OLD_KEY).encrypt("LEGACY"), null);
    store(damaged, "v1.1.not-a-real-ciphertext", null);
    final AesGcmWebhookSigningSecretCipher rotated =
        new AesGcmWebhookSigningSecretCipher(
            NEW_KEY, "2", "1:" + OLD_KEY, true, noBean(SecurityMetricsRecorder.class));

    final List<Result> results = sweep(rotated);

    assertThat(results).containsExactly(new Result(2, 1), new Result(1, 0));
    final AesGcmWebhookSigningSecretCipher onlyNew =
        new AesGcmWebhookSigningSecretCipher(
            NEW_KEY, "2", "", true, noBean(SecurityMetricsRecorder.class));
    assertThat(onlyNew.decrypt(rotating, column(rotating, "current_secret_encrypted")))
        .isEqualTo("CURRENT");
    assertThat(onlyNew.decrypt(rotating, column(rotating, "previous_secret_encrypted")))
        .isEqualTo("PREVIOUS");
    assertThat(onlyNew.decrypt(legacy, column(legacy, "current_secret_encrypted")))
        .isEqualTo("LEGACY");
    assertThat(column(legacy, "previous_secret_encrypted")).isNull();
    assertThat(column(damaged, "current_secret_encrypted")).isEqualTo("v1.1.not-a-real-ciphertext");
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
