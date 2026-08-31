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
 * shape as {@code JpaVerificationTokenRepositoryTest}.
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
  void savesAndFindsByAccountIdAndUserAgent() {
    KnownDevice device = KnownDevice.recognize(accountId, "Mozilla/5.0 Test Browser");

    repository.save(device);
    Optional<KnownDevice> found =
        repository.findByAccountIdAndUserAgent(accountId, "Mozilla/5.0 Test Browser");

    assertThat(found).isPresent();
    assertThat(found.get().id()).isEqualTo(device.id());
    assertThat(found.get().accountId()).isEqualTo(accountId);
    assertThat(found.get().userAgent()).isEqualTo("Mozilla/5.0 Test Browser");
  }

  @Test
  void findByAccountIdAndUserAgentIsEmptyForAnUnknownDevice() {
    assertThat(repository.findByAccountIdAndUserAgent(accountId, "Never Seen Browser")).isEmpty();
  }

  @Test
  void savingTheSameAccountIdAndUserAgentTwiceUpdatesTheExistingRow() {
    KnownDevice device = KnownDevice.recognize(accountId, "Mozilla/5.0 Test Browser");
    repository.save(device);
    device.touch();

    repository.save(device);

    KnownDevice found =
        repository.findByAccountIdAndUserAgent(accountId, "Mozilla/5.0 Test Browser").orElseThrow();
    assertThat(found.id()).isEqualTo(device.id());
    assertThat(found.lastSeenAt()).isAfterOrEqualTo(found.firstSeenAt());
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
