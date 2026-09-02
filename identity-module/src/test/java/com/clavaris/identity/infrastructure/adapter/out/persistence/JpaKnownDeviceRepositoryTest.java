package com.clavaris.identity.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.identity.application.usecase.recordaccountlogindevice.KnownDeviceRepository;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.KnownDevice;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * test-strategy.md §2: a real-Postgres integration test for the {@code KnownDevice} adapter — same
 * shape as {@code JpaVerificationTokenRepositoryTest}. TD-SEC-033: proves the real match key is now
 * {@code device_token_hash}, not {@code user_agent}.
 */
@SpringBootTest(classes = JpaKnownDeviceRepositoryTest.TestConfig.class)
@Testcontainers
@Transactional
class JpaKnownDeviceRepositoryTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Autowired private KnownDeviceRepository repository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private AccountId accountId;

  @BeforeEach
  void seedAnAccount() {
    accountId = new AccountId(UUID.randomUUID());
    jdbcTemplate.update(
        "insert into accounts (id, organization_id, email, status, created_at) "
            + "values (?, ?, ?, 'ACTIVE', now())",
        accountId.value(),
        UUID.randomUUID(),
        "known-device-owner-" + accountId.value() + "@example.com");
  }

  @Test
  void savesAndFindsByAccountIdAndDeviceTokenHash() {
    KnownDevice device =
        KnownDevice.recognize(accountId, "Mozilla/5.0 Test Browser", "a-token-hash");

    repository.save(device);
    Optional<KnownDevice> found =
        repository.findByAccountIdAndDeviceTokenHash(accountId, "a-token-hash");

    assertThat(found).isPresent();
    assertThat(found.get().id()).isEqualTo(device.id());
    assertThat(found.get().accountId()).isEqualTo(accountId);
    assertThat(found.get().userAgent()).isEqualTo("Mozilla/5.0 Test Browser");
    assertThat(found.get().deviceTokenHash()).isEqualTo("a-token-hash");
  }

  @Test
  void findByAccountIdAndDeviceTokenHashIsEmptyForAnUnknownToken() {
    assertThat(repository.findByAccountIdAndDeviceTokenHash(accountId, "never-issued")).isEmpty();
  }

  @Test
  void twoDevicesWithTheSameUserAgentButDifferentTokensAreBothStoredIndependently() {
    // TD-SEC-033: the old UNIQUE(account_id, user_agent) constraint is gone — the same browser/OS
    // string legitimately appears twice if cookies were cleared between visits, and that must not
    // be a constraint violation any more.
    KnownDevice first = KnownDevice.recognize(accountId, "Mozilla/5.0 Test Browser", "hash-one");
    KnownDevice second = KnownDevice.recognize(accountId, "Mozilla/5.0 Test Browser", "hash-two");

    repository.save(first);
    repository.save(second);

    assertThat(repository.findByAccountIdAndDeviceTokenHash(accountId, "hash-one")).isPresent();
    assertThat(repository.findByAccountIdAndDeviceTokenHash(accountId, "hash-two")).isPresent();
  }

  @Test
  void savingTheSameAccountIdAndDeviceTokenHashTwiceUpdatesTheExistingRow() {
    KnownDevice device =
        KnownDevice.recognize(accountId, "Mozilla/5.0 Test Browser", "a-token-hash");
    repository.save(device);
    device.touch();

    repository.save(device);

    KnownDevice found =
        repository.findByAccountIdAndDeviceTokenHash(accountId, "a-token-hash").orElseThrow();
    assertThat(found.id()).isEqualTo(device.id());
    assertThat(found.lastSeenAt()).isAfterOrEqualTo(found.firstSeenAt());
  }

  // Code review finding (2026-09-01): backs RecordAccountLoginDeviceService's migration
  // grandfather suppression — "does this Account have any known device row at all yet."
  @Test
  void existsByAccountIdIsFalseBeforeAnyDeviceIsSaved() {
    assertThat(repository.existsByAccountId(accountId)).isFalse();
  }

  @Test
  void existsByAccountIdIsTrueOnceADeviceHasBeenSaved() {
    repository.save(KnownDevice.recognize(accountId, "Mozilla/5.0 Test Browser", "a-token-hash"));

    assertThat(repository.existsByAccountId(accountId)).isTrue();
  }

  @Test
  void existsByAccountIdIsScopedToThisAccountOnly() {
    AccountId otherAccountId = new AccountId(UUID.randomUUID());
    jdbcTemplate.update(
        "insert into accounts (id, organization_id, email, status, created_at) "
            + "values (?, ?, ?, 'ACTIVE', now())",
        otherAccountId.value(),
        UUID.randomUUID(),
        "known-device-owner-" + otherAccountId.value() + "@example.com");
    repository.save(KnownDevice.recognize(otherAccountId, "Mozilla/5.0 Test Browser", "hash"));

    assertThat(repository.existsByAccountId(accountId)).isFalse();
  }

  @Configuration
  @EnableAutoConfiguration
  @EnableJpaRepositories(
      basePackageClasses = SpringDataKnownDeviceJpaRepository.class,
      includeFilters =
          @ComponentScan.Filter(
              type = FilterType.ASSIGNABLE_TYPE,
              classes = SpringDataKnownDeviceJpaRepository.class))
  @Import(JpaKnownDeviceRepository.class)
  static class TestConfig {}
}
