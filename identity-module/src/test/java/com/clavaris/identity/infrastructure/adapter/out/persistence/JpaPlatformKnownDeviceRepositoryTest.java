package com.clavaris.identity.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.identity.application.usecase.recordplatformaccountlogindevice.PlatformKnownDeviceRepository;
import com.clavaris.identity.domain.model.PlatformAccountId;
import com.clavaris.identity.domain.model.PlatformKnownDevice;
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
 * TD-FUT-026 (closed 2026-09-02): platform-tier mirror of {@code JpaKnownDeviceRepositoryTest} —
 * same real-Postgres shape, seeding {@code platform_accounts} instead of {@code accounts}.
 */
@SpringBootTest(classes = JpaPlatformKnownDeviceRepositoryTest.TestConfig.class)
@Testcontainers
@Transactional
class JpaPlatformKnownDeviceRepositoryTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Autowired private PlatformKnownDeviceRepository repository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private PlatformAccountId platformAccountId;

  @BeforeEach
  void seedAPlatformAccount() {
    platformAccountId = new PlatformAccountId(UUID.randomUUID());
    jdbcTemplate.update(
        "insert into platform_accounts (id, email, status, created_at) values (?, ?, 'ACTIVE', now())",
        platformAccountId.value(),
        "platform-known-device-owner-" + platformAccountId.value() + "@example.com");
  }

  @Test
  void savesAndFindsByPlatformAccountIdAndDeviceTokenHash() {
    PlatformKnownDevice device =
        PlatformKnownDevice.recognize(
            platformAccountId, "Mozilla/5.0 Test Browser", "a-token-hash");

    repository.save(device);
    Optional<PlatformKnownDevice> found =
        repository.findByPlatformAccountIdAndDeviceTokenHash(platformAccountId, "a-token-hash");

    assertThat(found).isPresent();
    assertThat(found.get().id()).isEqualTo(device.id());
    assertThat(found.get().platformAccountId()).isEqualTo(platformAccountId);
    assertThat(found.get().userAgent()).isEqualTo("Mozilla/5.0 Test Browser");
    assertThat(found.get().deviceTokenHash()).isEqualTo("a-token-hash");
  }

  @Test
  void findByPlatformAccountIdAndDeviceTokenHashIsEmptyForAnUnknownToken() {
    assertThat(
            repository.findByPlatformAccountIdAndDeviceTokenHash(platformAccountId, "never-issued"))
        .isEmpty();
  }

  @Test
  void twoDevicesWithTheSameUserAgentButDifferentTokensAreBothStoredIndependently() {
    PlatformKnownDevice first =
        PlatformKnownDevice.recognize(platformAccountId, "Mozilla/5.0 Test Browser", "hash-one");
    PlatformKnownDevice second =
        PlatformKnownDevice.recognize(platformAccountId, "Mozilla/5.0 Test Browser", "hash-two");

    repository.save(first);
    repository.save(second);

    assertThat(repository.findByPlatformAccountIdAndDeviceTokenHash(platformAccountId, "hash-one"))
        .isPresent();
    assertThat(repository.findByPlatformAccountIdAndDeviceTokenHash(platformAccountId, "hash-two"))
        .isPresent();
  }

  @Test
  void savingTheSamePlatformAccountIdAndDeviceTokenHashTwiceUpdatesTheExistingRow() {
    PlatformKnownDevice device =
        PlatformKnownDevice.recognize(
            platformAccountId, "Mozilla/5.0 Test Browser", "a-token-hash");
    repository.save(device);
    device.touch();

    repository.save(device);

    PlatformKnownDevice found =
        repository
            .findByPlatformAccountIdAndDeviceTokenHash(platformAccountId, "a-token-hash")
            .orElseThrow();
    assertThat(found.id()).isEqualTo(device.id());
    assertThat(found.lastSeenAt()).isAfterOrEqualTo(found.firstSeenAt());
  }

  @Test
  void existsByPlatformAccountIdIsFalseBeforeAnyDeviceIsSaved() {
    assertThat(repository.existsByPlatformAccountId(platformAccountId)).isFalse();
  }

  @Test
  void existsByPlatformAccountIdIsTrueOnceADeviceHasBeenSaved() {
    repository.save(
        PlatformKnownDevice.recognize(
            platformAccountId, "Mozilla/5.0 Test Browser", "a-token-hash"));

    assertThat(repository.existsByPlatformAccountId(platformAccountId)).isTrue();
  }

  @Test
  void existsByPlatformAccountIdIsScopedToThisPlatformAccountOnly() {
    PlatformAccountId otherPlatformAccountId = new PlatformAccountId(UUID.randomUUID());
    jdbcTemplate.update(
        "insert into platform_accounts (id, email, status, created_at) values (?, ?, 'ACTIVE', now())",
        otherPlatformAccountId.value(),
        "platform-known-device-owner-" + otherPlatformAccountId.value() + "@example.com");
    repository.save(
        PlatformKnownDevice.recognize(otherPlatformAccountId, "Mozilla/5.0 Test Browser", "hash"));

    assertThat(repository.existsByPlatformAccountId(platformAccountId)).isFalse();
  }

  @Configuration
  @EnableAutoConfiguration
  @EnableJpaRepositories(
      basePackageClasses = SpringDataPlatformKnownDeviceJpaRepository.class,
      includeFilters =
          @ComponentScan.Filter(
              type = FilterType.ASSIGNABLE_TYPE,
              classes = SpringDataPlatformKnownDeviceJpaRepository.class))
  @Import(JpaPlatformKnownDeviceRepository.class)
  static class TestConfig {}
}
