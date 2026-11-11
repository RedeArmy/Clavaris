package com.clavaris.webhook.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.webhook.application.usecase.deliverpendingwebhooks.WebhookDeliveryRepository;
import jakarta.persistence.EntityManagerFactory;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
 * TD-PERF-057, against a real Postgres: erasing the deliveries of an Organization is one set-based
 * statement however many it owns. It used to be a Spring Data derived delete, which loads every
 * entity and removes it on its own, and {@code webhook_deliveries} is the table that grows fastest:
 * a row per event per endpoint. Counted in the statements Hibernate prepares, because both ways
 * remove the same rows.
 */
@SpringBootTest(classes = JpaWebhookDeliveryBulkDeleteTest.TestConfig.class)
@Testcontainers
@Transactional
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class JpaWebhookDeliveryBulkDeleteTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Autowired private WebhookDeliveryRepository repository;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EntityManagerFactory entityManagerFactory;

  @Test
  void erasingAnOrganizationsDeliveriesIsOneStatementNotOnePerDelivery() {
    final UUID organization = UUID.randomUUID();
    final UUID other = UUID.randomUUID();
    final UUID endpoint = persistedEndpoint();
    for (int i = 0; i < 60; i++) {
      insertDelivery(endpoint, organization);
    }
    insertDelivery(endpoint, other);
    final Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    statistics.clear();

    repository.deleteAllByOrganizationId(organization);

    assertThat(statistics.getPrepareStatementCount())
        .as("a derived delete prepares one statement per delivery, this one prepares one in all")
        .isLessThan(5);
    assertThat(count(organization)).isZero();
    assertThat(count(other)).as("the other Organization keeps its delivery").isEqualTo(1);
  }

  private UUID persistedEndpoint() {
    final UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into webhook_endpoints (id, organization_id, url, subscribed_event_types,"
            + " current_secret_encrypted) values (?, ?, 'https://example.com/hook', '[\"x\"]', 's')",
        id,
        UUID.randomUUID());
    return id;
  }

  private void insertDelivery(final UUID endpointId, final UUID organizationId) {
    jdbc.update(
        "insert into webhook_deliveries (id, endpoint_id, organization_id, outbox_event_id,"
            + " aggregate_type, aggregate_id, event_type, payload, status, attempt_count,"
            + " created_at) values (?, ?, ?, ?, 'Account', ?, 'account.created', '{}',"
            + " 'SUCCEEDED', 0, now())",
        UUID.randomUUID(),
        endpointId,
        organizationId,
        UUID.randomUUID(),
        UUID.randomUUID());
  }

  private int count(final UUID organizationId) {
    return jdbc.queryForObject(
        "select count(*) from webhook_deliveries where organization_id = ?",
        Integer.class,
        organizationId);
  }

  @Configuration
  @EnableAutoConfiguration
  @EnableJpaRepositories(basePackageClasses = SpringDataWebhookDeliveryJpaRepository.class)
  @Import(JpaWebhookDeliveryRepository.class)
  static class TestConfig {}
}
