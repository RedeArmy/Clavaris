package com.clavaris.webhook.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.webhook.application.usecase.dispatchoutboxevents.OutboxEvent;
import com.clavaris.webhook.application.usecase.dispatchoutboxevents.OutboxEventReader;
import com.clavaris.webhook.application.usecase.dispatchoutboxevents.OutboxSource;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real-Postgres integration test for {@link JpaOutboxEventReader} — proves it reads real rows out
 * of identity-module's/organization-module's own physical outbox tables (ADR-0007 §1's own data
 * contract) via this module's independently-owned read-side entities, never a mocked port. This
 * module deliberately doesn't depend on either producer module, so {@code event_outbox}/{@code
 * organization_event_outbox} are created here by hand, matching their real migrations' final shape
 * (post {@code V20260902140000}/{@code V20260902140001}) exactly — not by pulling in those modules'
 * own Flyway migrations, which would reintroduce the very dependency ADR-0007 §1 forbids.
 */
@SpringBootTest(classes = JpaOutboxEventReaderTest.TestConfig.class)
@Testcontainers
@Transactional
class JpaOutboxEventReaderTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Autowired private OutboxEventReader reader;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void createProducerOwnedOutboxTables() {
    jdbcTemplate.execute(
        "create table if not exists event_outbox ("
            + "id uuid primary key, organization_id uuid not null, aggregate_type varchar(100) not"
            + " null, aggregate_id uuid not null, event_type varchar(100) not null, payload text,"
            + " trace_id varchar(32),"
            + " occurred_at timestamptz not null default now(), published_at timestamptz)");
    jdbcTemplate.execute(
        "create table if not exists organization_event_outbox ("
            + "id uuid primary key, organization_id uuid not null, aggregate_type varchar(100) not"
            + " null, aggregate_id uuid not null, event_type varchar(100) not null, payload text,"
            + " trace_id varchar(32),"
            + " occurred_at timestamptz not null default now(), published_at timestamptz)");
  }

  @Test
  void claimUnpublishedBatchReadsRowsFromBothProducerTables() {
    UUID identityOrgId = UUID.randomUUID();
    UUID organizationModuleOrgId = UUID.randomUUID();
    insertIdentityOutboxRow(identityOrgId, "account.created");
    insertOrganizationOutboxRow(organizationModuleOrgId, "workspace_membership.removed");

    List<OutboxEvent> claimed = reader.claimUnpublishedBatch(10);

    assertThat(claimed).hasSize(2);
    assertThat(claimed)
        .extracting(OutboxEvent::source)
        .containsExactlyInAnyOrder(OutboxSource.IDENTITY, OutboxSource.ORGANIZATION);
    assertThat(claimed)
        .extracting(OutboxEvent::organizationId)
        .containsExactlyInAnyOrder(identityOrgId, organizationModuleOrgId);
    assertThat(claimed)
        .extracting(OutboxEvent::eventType)
        .containsExactlyInAnyOrder("account.created", "workspace_membership.removed");
  }

  @Test
  void claimUnpublishedBatchNeverReturnsAnAlreadyPublishedRow() {
    UUID id = insertIdentityOutboxRow(UUID.randomUUID(), "account.created");
    jdbcTemplate.update("update event_outbox set published_at = now() where id = ?", id);

    List<OutboxEvent> claimed = reader.claimUnpublishedBatch(10);

    assertThat(claimed).isEmpty();
  }

  @Test
  void markPublishedStopsAnIdentityRowFromBeingClaimedAgain() {
    UUID id = insertIdentityOutboxRow(UUID.randomUUID(), "account.created");
    OutboxEvent event = reader.claimUnpublishedBatch(10).get(0);

    reader.markPublished(event);

    Instant publishedAt =
        jdbcTemplate.queryForObject(
            "select published_at from event_outbox where id = ?", Instant.class, id);
    assertThat(publishedAt).isNotNull();
    assertThat(reader.claimUnpublishedBatch(10)).isEmpty();
  }

  @Test
  void markPublishedStopsAnOrganizationRowFromBeingClaimedAgain() {
    UUID id = insertOrganizationOutboxRow(UUID.randomUUID(), "workspace_membership.removed");
    OutboxEvent event = reader.claimUnpublishedBatch(10).get(0);

    reader.markPublished(event);

    Instant publishedAt =
        jdbcTemplate.queryForObject(
            "select published_at from organization_event_outbox where id = ?", Instant.class, id);
    assertThat(publishedAt).isNotNull();
    assertThat(reader.claimUnpublishedBatch(10)).isEmpty();
  }

  @Test
  void claimUnpublishedBatchReadsTheTraceIdWhenTheSourceRowCarriesOne() {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "insert into event_outbox (id, organization_id, aggregate_type, aggregate_id, event_type,"
            + " payload, trace_id, occurred_at) values (?, ?, 'Account', ?, ?, '{}', ?, now())",
        id,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "account.created",
        "trace-abc123");

    OutboxEvent claimed = reader.claimUnpublishedBatch(10).get(0);

    assertThat(claimed.traceId()).isEqualTo("trace-abc123");
  }

  private UUID insertIdentityOutboxRow(final UUID organizationId, final String eventType) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "insert into event_outbox (id, organization_id, aggregate_type, aggregate_id, event_type,"
            + " payload, occurred_at) values (?, ?, 'Account', ?, ?, '{}', now())",
        id,
        organizationId,
        UUID.randomUUID(),
        eventType);
    return id;
  }

  private UUID insertOrganizationOutboxRow(final UUID organizationId, final String eventType) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "insert into organization_event_outbox (id, organization_id, aggregate_type,"
            + " aggregate_id, event_type, payload, occurred_at) values (?, ?,"
            + " 'WorkspaceMembership', ?, ?, '{}', now())",
        id,
        organizationId,
        UUID.randomUUID(),
        eventType);
    return id;
  }

  @Configuration
  @EnableAutoConfiguration
  @EnableJpaRepositories(
      basePackageClasses = {
        SpringDataIdentityOutboxRowJpaRepository.class,
        SpringDataOrganizationOutboxRowJpaRepository.class
      })
  @Import(JpaOutboxEventReader.class)
  static class TestConfig {}
}
