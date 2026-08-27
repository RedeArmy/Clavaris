package com.clavaris.organization.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
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
 * TD-ARCH-007: identity-module's own {@code EventOutboxRetentionJobTest} mirror — proves {@link
 * OrganizationEventOutboxRetentionJob} sweeps this module's own {@code organization_event_outbox}
 * by age alone, not by {@code published_at} (see that class's own Javadoc for the full "why"), and
 * exercises both branches of its own post-sweep logging decision (some rows swept were never
 * published vs. all of them already were).
 */
@SpringBootTest(classes = OrganizationEventOutboxRetentionJobTest.TestConfig.class)
@Testcontainers
@TestPropertySource(properties = "clavaris.event-outbox.retention-days=90")
@Transactional
class OrganizationEventOutboxRetentionJobTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Autowired private OrganizationEventOutboxRetentionJob job;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void sweepsRowsOlderThanTheRetentionWindowRegardlessOfPublishedStatus() {
    insertRow(Instant.now().minus(100, ChronoUnit.DAYS), null); // old, never published
    insertRow(Instant.now().minus(100, ChronoUnit.DAYS), Instant.now().minus(99, ChronoUnit.DAYS));
    insertRow(Instant.now().minus(1, ChronoUnit.DAYS), null); // recent, kept

    job.sweepExpiredRows();

    Long remaining =
        jdbcTemplate.queryForObject("select count(*) from organization_event_outbox", Long.class);
    assertThat(remaining).isEqualTo(1L);
  }

  @Test
  void leavesEverythingAloneWhenNothingIsPastTheWindow() {
    insertRow(Instant.now().minus(1, ChronoUnit.DAYS), null);
    insertRow(Instant.now(), null);

    job.sweepExpiredRows();

    Long remaining =
        jdbcTemplate.queryForObject("select count(*) from organization_event_outbox", Long.class);
    assertThat(remaining).isEqualTo(2L);
  }

  @Test
  void sweepsRowsThatWereAllAlreadyPublishedWithoutTreatingThatAsAnAnomaly() {
    // Distinct from the mixed-status case above: every swept row was already published, so the
    // job's own "deleted > 0 && stillUnpublished == 0" branch (plain info log, not a warning) is
    // the one exercised here — both post-sweep logging outcomes now have real coverage.
    insertRow(Instant.now().minus(100, ChronoUnit.DAYS), Instant.now().minus(99, ChronoUnit.DAYS));

    job.sweepExpiredRows();

    Long remaining =
        jdbcTemplate.queryForObject("select count(*) from organization_event_outbox", Long.class);
    assertThat(remaining).isEqualTo(0L);
  }

  private void insertRow(final Instant occurredAt, final Instant publishedAt) {
    jdbcTemplate.update(
        "insert into organization_event_outbox (id, aggregate_type, aggregate_id, event_type,"
            + " payload, occurred_at, published_at) values (?, 'Organization', ?,"
            + " 'organization.deleted', '{}', ?, ?)",
        UUID.randomUUID(),
        UUID.randomUUID(),
        Timestamp.from(occurredAt),
        publishedAt == null ? null : Timestamp.from(publishedAt));
  }

  @Configuration
  @EnableAutoConfiguration
  @EnableJpaRepositories(
      basePackageClasses = SpringDataOrganizationEventOutboxJpaRepository.class,
      includeFilters =
          @ComponentScan.Filter(
              type = FilterType.ASSIGNABLE_TYPE,
              classes = SpringDataOrganizationEventOutboxJpaRepository.class))
  @Import(OrganizationEventOutboxRetentionJob.class)
  static class TestConfig {}
}
