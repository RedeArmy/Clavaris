package com.clavaris.webhook.infrastructure.adapter.out.persistence;

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
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * ADR-0007's own second open question, resolved (see {@link WebhookDeliveryRetentionJob}'s own
 * Javadoc): proves the sweep is terminal-status-and-age gated, not one or the other alone — same
 * shape as {@code KnownDeviceRetentionJobTest}/{@code EventOutboxRetentionJobTest} elsewhere in
 * this codebase.
 *
 * <p>Verification below reads row counts back through {@link
 * SpringDataWebhookDeliveryJpaRepository} (JPA), not raw JDBC — {@code
 * deleteByCreatedAtBeforeAndStatusIn} is a Spring Data JPA derived delete, which stages its
 * removals in Hibernate's own persistence context and only flushes them to the connection on the
 * next JPA operation (or commit); a raw {@code JdbcTemplate} read run immediately after, on the
 * very same transaction, has no visibility into that context and would see the pre-delete row count
 * regardless of whether the sweep actually matched anything.
 */
@SpringBootTest(classes = WebhookDeliveryRetentionJobTest.TestConfig.class)
@Testcontainers
@TestPropertySource(properties = "clavaris.webhook.delivery-retention-days=90")
@Transactional
class WebhookDeliveryRetentionJobTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Autowired private WebhookDeliveryRetentionJob job;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private SpringDataWebhookDeliveryJpaRepository deliveries;

  // Inserted via raw JDBC, not the JPA repository's plain (unflushed) save — this class mixes
  // JdbcTemplate and JPA in the same @Transactional test, and a plain save() only stages the
  // insert in Hibernate's persistence context, invisible to a raw JDBC statement run right after
  // it on the same connection until an explicit flush happens.
  private UUID persistedEndpointId() {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "insert into webhook_endpoints (id, organization_id, url, subscribed_event_types,"
            + " current_secret_encrypted) values (?, ?, 'https://example.com/hook', '[\"x\"]',"
            + " 's')",
        id,
        UUID.randomUUID());
    return id;
  }

  @Test
  void sweepsTerminalRowsOlderThanTheRetentionWindow() {
    insertRow("SUCCEEDED", Instant.now().minus(91, ChronoUnit.DAYS));
    insertRow("SUCCEEDED", Instant.now().minus(1, ChronoUnit.DAYS));

    job.sweepExpiredRows();

    assertThat(deliveries.count()).isEqualTo(1L);
  }

  @Test
  void neverSweepsAnOldPendingOrFailedRow_theirOwnNextAttemptMayStillBeLegitimatelyDue() {
    insertRow("PENDING", Instant.now().minus(200, ChronoUnit.DAYS));
    insertRow("FAILED", Instant.now().minus(200, ChronoUnit.DAYS));

    job.sweepExpiredRows();

    assertThat(deliveries.count()).isEqualTo(2L);
  }

  @Test
  void leavesEverythingAloneWhenNothingIsPastTheWindow() {
    insertRow("SUCCEEDED", Instant.now().minus(1, ChronoUnit.DAYS));
    insertRow("EXHAUSTED", Instant.now());

    job.sweepExpiredRows();

    assertThat(deliveries.count()).isEqualTo(2L);
  }

  private void insertRow(final String status, final Instant createdAt) {
    jdbcTemplate.update(
        "insert into webhook_deliveries (id, endpoint_id, organization_id, outbox_event_id,"
            + " aggregate_type, aggregate_id, event_type, payload, status, attempt_count,"
            + " created_at) values (?, ?, ?, ?, 'Account', ?, 'account.created', '{}', ?, 0, ?)",
        UUID.randomUUID(),
        persistedEndpointId(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        status,
        Timestamp.from(createdAt));
  }

  @Configuration
  @EnableAutoConfiguration
  @EnableJpaRepositories(basePackageClasses = SpringDataWebhookDeliveryJpaRepository.class)
  @Import(WebhookDeliveryRetentionJob.class)
  static class TestConfig {}
}
