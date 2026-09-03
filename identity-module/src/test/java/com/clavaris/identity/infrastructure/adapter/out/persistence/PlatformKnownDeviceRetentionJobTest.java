package com.clavaris.identity.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** TD-FUT-026 (closed 2026-09-02): platform-tier mirror of {@code KnownDeviceRetentionJobTest}. */
@SpringBootTest(classes = PlatformKnownDeviceRetentionJobTest.TestConfig.class)
@Testcontainers
@TestPropertySource(properties = "clavaris.platform-known-device.retention-days=400")
@Transactional
class PlatformKnownDeviceRetentionJobTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Autowired private PlatformKnownDeviceRetentionJob job;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID platformAccountId;

  @BeforeEach
  void seedAPlatformAccount() {
    platformAccountId = UUID.randomUUID();
    jdbcTemplate.update(
        "insert into platform_accounts (id, email, status, created_at) values (?, ?, 'ACTIVE', now())",
        platformAccountId,
        "platform-known-device-retention-owner-" + platformAccountId + "@example.com");
  }

  @Test
  void sweepsRowsWhoseLastSeenAtIsPastTheRetentionWindow() {
    insertRow(Instant.now().minus(401, ChronoUnit.DAYS));
    insertRow(Instant.now().minus(1, ChronoUnit.DAYS));

    job.sweepStaleDevices();

    Long remaining =
        jdbcTemplate.queryForObject("select count(*) from platform_known_devices", Long.class);
    assertThat(remaining).isEqualTo(1L);
  }

  @Test
  void leavesAStillActiveOldDeviceAloneBecauseItWasRecentlyTouched() {
    jdbcTemplate.update(
        "insert into platform_known_devices (id, platform_account_id, user_agent,"
            + " device_token_hash, first_seen_at, last_seen_at) values (?, ?, 'Mozilla/5.0', ?, ?,"
            + " ?)",
        UUID.randomUUID(),
        platformAccountId,
        "hash-" + UUID.randomUUID(),
        Timestamp.from(Instant.now().minus(500, ChronoUnit.DAYS)),
        Timestamp.from(Instant.now()));

    job.sweepStaleDevices();

    Long remaining =
        jdbcTemplate.queryForObject("select count(*) from platform_known_devices", Long.class);
    assertThat(remaining).isEqualTo(1L);
  }

  @Test
  void leavesEverythingAloneWhenNothingIsPastTheWindow() {
    insertRow(Instant.now().minus(1, ChronoUnit.DAYS));
    insertRow(Instant.now());

    job.sweepStaleDevices();

    Long remaining =
        jdbcTemplate.queryForObject("select count(*) from platform_known_devices", Long.class);
    assertThat(remaining).isEqualTo(2L);
  }

  private void insertRow(final Instant lastSeenAt) {
    jdbcTemplate.update(
        "insert into platform_known_devices (id, platform_account_id, user_agent,"
            + " device_token_hash, first_seen_at, last_seen_at) values (?, ?, 'Mozilla/5.0', ?, ?,"
            + " ?)",
        UUID.randomUUID(),
        platformAccountId,
        "hash-" + UUID.randomUUID(),
        Timestamp.from(lastSeenAt),
        Timestamp.from(lastSeenAt));
  }

  @Configuration
  @EnableAutoConfiguration
  @EnableJpaRepositories(
      basePackageClasses = SpringDataPlatformKnownDeviceJpaRepository.class,
      includeFilters =
          @ComponentScan.Filter(
              type = FilterType.ASSIGNABLE_TYPE,
              classes = SpringDataPlatformKnownDeviceJpaRepository.class))
  @Import(PlatformKnownDeviceRetentionJob.class)
  static class TestConfig {}
}
