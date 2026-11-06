package com.clavaris.identity.infrastructure.adapter.out.crypto;

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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * TD-SEC-089 against a real schema: a value written under the old key, and one written before the
 * envelope format, end up readable with the new key alone, and a value that cannot be read does not
 * stop the rest.
 */
@SpringBootTest(classes = TotpSecretReencryptionTest.TestConfig.class)
@Testcontainers
class TotpSecretReencryptionTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  private static final String OLD_KEY = newKey();
  private static final String NEW_KEY = newKey();

  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager transactionManager;

  private com.clavaris.identity.domain.model.AccountId underOld;
  private com.clavaris.identity.domain.model.AccountId legacy;
  private com.clavaris.identity.domain.model.AccountId damaged;

  // Autowired fields exist only after construction, so the accounts cannot be field initialisers.
  @org.junit.jupiter.api.BeforeEach
  void seedAccounts() {
    underOld = newAccount();
    legacy = newAccount();
    damaged = newAccount();
  }

  private com.clavaris.identity.domain.model.AccountId newAccount() {
    final var id = new com.clavaris.identity.domain.model.AccountId(UUID.randomUUID());
    inTransaction(
        () -> {
          jdbc.update(
              "insert into accounts (id, organization_id, email, status, created_at)"
                  + " values (?, ?, ?, 'ACTIVE', now())",
              id.value(),
              UUID.randomUUID(),
              "totp-" + id.value() + "@example.com");
          jdbc.update(
              "insert into password_credentials (account_id, password_hash) values (?, ?)",
              id.value(),
              "not-a-real-hash-this-suite-never-logs-in");
        });
    return id;
  }

  private void store(final com.clavaris.identity.domain.model.AccountId id, final String value) {
    jdbc.update(
        "insert into totp_credentials (account_id, organization_id, secret_encrypted)"
            + " values (?, ?, ?)",
        id.value(),
        UUID.randomUUID(),
        value);
  }

  private String stored(final com.clavaris.identity.domain.model.AccountId id) {
    return jdbc.queryForObject(
        "select secret_encrypted from totp_credentials where account_id = ?",
        String.class,
        id.value());
  }

  @Test
  void everyReadableSecretEndsUpUnderTheNewKeyAlone() {
    final AesGcmTotpSecretCipher old =
        new AesGcmTotpSecretCipher(OLD_KEY, "1", "", noBean(SecurityMetricsRecorder.class));
    store(underOld, old.encrypt(underOld, "SECRETONE"));
    store(legacy, new AesGcmCipher(OLD_KEY).encrypt("SECRETTWO"));
    store(damaged, "v1.1.not-a-real-ciphertext");
    final AesGcmTotpSecretCipher rotated =
        new AesGcmTotpSecretCipher(
            NEW_KEY, "2", "1:" + OLD_KEY, noBean(SecurityMetricsRecorder.class));

    final List<Result> results = sweep(rotated);

    assertThat(results).containsExactly(new Result(2, 1));
    final AesGcmTotpSecretCipher onlyNew =
        new AesGcmTotpSecretCipher(NEW_KEY, "2", "", noBean(SecurityMetricsRecorder.class));
    assertThat(onlyNew.decrypt(underOld, stored(underOld))).isEqualTo("SECRETONE");
    assertThat(onlyNew.decrypt(legacy, stored(legacy))).isEqualTo("SECRETTWO");
    assertThat(stored(damaged)).isEqualTo("v1.1.not-a-real-ciphertext");
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

  private void inTransaction(final Runnable work) {
    new TransactionTemplate(transactionManager).executeWithoutResult(status -> work.run());
  }

  @Configuration
  @EnableAutoConfiguration
  static class TestConfig {}
}
