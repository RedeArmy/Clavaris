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

/**
 * TD-PERF-002: proves {@link KnownDeviceRetentionJob} sweeps by {@code last_seen_at} age alone —
 * the load-bearing design decision explained in that class's own Javadoc (a still-active device
 * must never be swept just because it's old; only one genuinely gone quiet for the full window is a
 * candidate).
 */
@SpringBootTest(classes = KnownDeviceRetentionJobTest.TestConfig.class)
@Testcontainers
@TestPropertySource(properties = "clavaris.known-device.retention-days=400")
@Transactional
class KnownDeviceRetentionJobTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Autowired private KnownDeviceRetentionJob job;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID accountId;

  @BeforeEach
  void seedAnAccount() {
    accountId = UUID.randomUUID();
    jdbcTemplate.update(
        "insert into accounts (id, organization_id, email, status, created_at) "
            + "values (?, ?, ?, 'ACTIVE', now())",
        accountId,
        UUID.randomUUID(),
        "known-device-retention-owner-" + accountId + "@example.com");
  }

  @Test
  void sweepsRowsWhoseLastSeenAtIsPastTheRetentionWindow() {
    insertRow(Instant.now().minus(401, ChronoUnit.DAYS)); // stale — swept
    insertRow(Instant.now().minus(1, ChronoUnit.DAYS)); // recent — kept

    job.sweepStaleDevices();

    Long remaining = jdbcTemplate.queryForObject("select count(*) from known_devices", Long.class);
    assertThat(remaining).isEqualTo(1L);
  }

  @Test
  void leavesAStillActiveOldDeviceAloneBecauseItWasRecentlyTouched() {
    // first_seen_at is old (this device has been known for a long time), but last_seen_at is
    // recent (it's still genuinely in regular use) — must survive the sweep.
    jdbcTemplate.update(
        "insert into known_devices (id, account_id, user_agent, device_token_hash,"
            + " first_seen_at, last_seen_at) values (?, ?, 'Mozilla/5.0', ?, ?, ?)",
        UUID.randomUUID(),
        accountId,
        "hash-" + UUID.randomUUID(),
        Timestamp.from(Instant.now().minus(500, ChronoUnit.DAYS)),
        Timestamp.from(Instant.now()));

    job.sweepStaleDevices();

    Long remaining = jdbcTemplate.queryForObject("select count(*) from known_devices", Long.class);
    assertThat(remaining).isEqualTo(1L);
  }

  @Test
  void leavesEverythingAloneWhenNothingIsPastTheWindow() {
    insertRow(Instant.now().minus(1, ChronoUnit.DAYS));
    insertRow(Instant.now());

    job.sweepStaleDevices();

    Long remaining = jdbcTemplate.queryForObject("select count(*) from known_devices", Long.class);
    assertThat(remaining).isEqualTo(2L);
  }

  private void insertRow(final Instant lastSeenAt) {
    jdbcTemplate.update(
        "insert into known_devices (id, account_id, user_agent, device_token_hash,"
            + " first_seen_at, last_seen_at) values (?, ?, 'Mozilla/5.0', ?, ?, ?)",
        UUID.randomUUID(),
        accountId,
        "hash-" + UUID.randomUUID(),
        Timestamp.from(lastSeenAt),
        Timestamp.from(lastSeenAt));
  }

  @Configuration
  @EnableAutoConfiguration
  @EnableJpaRepositories(
      basePackageClasses = SpringDataKnownDeviceJpaRepository.class,
      includeFilters =
          @ComponentScan.Filter(
              type = FilterType.ASSIGNABLE_TYPE,
              classes = SpringDataKnownDeviceJpaRepository.class))
  @Import(KnownDeviceRetentionJob.class)
  static class TestConfig {}
}
