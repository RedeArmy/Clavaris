package com.clavaris.identity.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.common.infrastructure.adapter.out.persistence.PostgresAdvisoryJobLock;
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
 * TD-TEST-002: proves {@link EventOutboxRetentionJob} sweeps by age alone, not by {@code
 * published_at} — the load-bearing design decision explained in that class's own Javadoc, since
 * webhook-module (ADR-0007) has no dispatcher yet and every row's {@code published_at} is always
 * {@code NULL} in this codebase today.
 */
@SpringBootTest(classes = EventOutboxRetentionJobTest.TestConfig.class)
@Testcontainers
@TestPropertySource(properties = "clavaris.event-outbox.retention-days=90")
@Transactional
class EventOutboxRetentionJobTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Autowired private EventOutboxRetentionJob job;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void sweepsRowsOlderThanTheRetentionWindowRegardlessOfPublishedStatus() {
    insertRow(Instant.now().minus(100, ChronoUnit.DAYS), null); // old, never published
    insertRow(Instant.now().minus(100, ChronoUnit.DAYS), Instant.now().minus(99, ChronoUnit.DAYS));
    insertRow(Instant.now().minus(1, ChronoUnit.DAYS), null); // recent, kept

    job.sweepExpiredRows();

    Long remaining = jdbcTemplate.queryForObject("select count(*) from event_outbox", Long.class);
    assertThat(remaining).isEqualTo(1L);
  }

  @Test
  void leavesEverythingAloneWhenNothingIsPastTheWindow() {
    insertRow(Instant.now().minus(1, ChronoUnit.DAYS), null);
    insertRow(Instant.now(), null);

    job.sweepExpiredRows();

    Long remaining = jdbcTemplate.queryForObject("select count(*) from event_outbox", Long.class);
    assertThat(remaining).isEqualTo(2L);
  }

  // SDE-III review, 2026-09-15: the real regression this guards — before this fix, a swept
  // still-unpublished row was gone for good the moment deleteByOccurredAtBefore ran; only a WARN
  // log's count survived it. Proves the row's full content now survives the sweep somewhere an
  // operator can actually go look at, not just a number.
  @Test
  void unpublishedRowsSweptPastRetentionAreArchivedToTheDeadLetterTable() {
    UUID id = UUID.randomUUID();
    Instant occurredAt = Instant.now().minus(100, ChronoUnit.DAYS);
    jdbcTemplate.update(
        "insert into event_outbox (id, organization_id, aggregate_type, aggregate_id, event_type,"
            + " payload, occurred_at, published_at) values (?, ?, 'Account', ?, 'account.created',"
            + " '{\"foo\":\"bar\"}', ?, null)",
        id,
        UUID.randomUUID(),
        UUID.randomUUID(),
        Timestamp.from(occurredAt));

    job.sweepExpiredRows();

    Long deadLetterCount =
        jdbcTemplate.queryForObject(
            "select count(*) from event_outbox_dead_letters where id = ?", Long.class, id);
    assertThat(deadLetterCount).isEqualTo(1L);
    String payload =
        jdbcTemplate.queryForObject(
            "select payload from event_outbox_dead_letters where id = ?", String.class, id);
    assertThat(payload).isEqualTo("{\"foo\":\"bar\"}");
  }

  // Companion to the test above: a row that WAS successfully published never needed a dead
  // letter in the first place — only rows nothing ever consumed are worth preserving.
  @Test
  void publishedRowsSweptPastRetentionAreNotArchivedToTheDeadLetterTable() {
    insertRow(Instant.now().minus(100, ChronoUnit.DAYS), Instant.now().minus(99, ChronoUnit.DAYS));

    job.sweepExpiredRows();

    Long deadLetterCount =
        jdbcTemplate.queryForObject("select count(*) from event_outbox_dead_letters", Long.class);
    assertThat(deadLetterCount).isZero();
  }

  private void insertRow(final Instant occurredAt, final Instant publishedAt) {
    jdbcTemplate.update(
        "insert into event_outbox (id, organization_id, aggregate_type, aggregate_id, event_type,"
            + " payload, occurred_at, published_at) values (?, ?, 'Account', ?, 'account.created',"
            + " '{}', ?, ?)",
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        Timestamp.from(occurredAt),
        publishedAt == null ? null : Timestamp.from(publishedAt));
  }

  @Configuration
  @EnableAutoConfiguration
  @EnableJpaRepositories(
      basePackageClasses = SpringDataEventOutboxJpaRepository.class,
      includeFilters =
          @ComponentScan.Filter(
              type = FilterType.ASSIGNABLE_TYPE,
              classes = SpringDataEventOutboxJpaRepository.class))
  @Import({EventOutboxRetentionJob.class, PostgresAdvisoryJobLock.class})
  static class TestConfig {}
}
