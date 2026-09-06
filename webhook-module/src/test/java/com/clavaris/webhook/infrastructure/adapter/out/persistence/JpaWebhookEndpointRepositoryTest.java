package com.clavaris.webhook.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.webhook.application.usecase.registerwebhookendpoint.WebhookEndpointRepository;
import com.clavaris.webhook.domain.model.WebhookEndpoint;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real-Postgres integration test for the WebhookEndpoint persistence adapter (migration {@code
 * V20260902120000}) — {@code organization_id} has no FK here (a deliberate cross-module boundary,
 * this table's own migration comment), so every test below uses a bare random {@link UUID} freely.
 */
@SpringBootTest(classes = JpaWebhookEndpointRepositoryTest.TestConfig.class)
@Testcontainers
class JpaWebhookEndpointRepositoryTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Autowired private WebhookEndpointRepository repository;

  @Test
  void savesAndReadsBackAnEndpoint_reconstituteKeepsTheRealPersistedFields() {
    UUID organizationId = UUID.randomUUID();
    WebhookEndpoint endpoint =
        WebhookEndpoint.register(
            organizationId,
            "https://example.com/webhooks",
            "Production",
            List.of("account.created", "account.deleted"),
            "encrypted-secret");

    repository.save(endpoint);
    WebhookEndpoint found = repository.findById(endpoint.id()).orElseThrow();

    assertThat(found.id()).isEqualTo(endpoint.id());
    assertThat(found.organizationId()).isEqualTo(organizationId);
    assertThat(found.url()).isEqualTo("https://example.com/webhooks");
    assertThat(found.description()).isEqualTo("Production");
    assertThat(found.subscribedEventTypes())
        .containsExactlyInAnyOrder("account.created", "account.deleted");
    assertThat(found.currentSecretEncrypted()).isEqualTo("encrypted-secret");
    assertThat(found.active()).isTrue();
  }

  @Test
  void savingARotatedEndpointPersistsBothTheCurrentAndPreviousSecret() {
    WebhookEndpoint endpoint =
        WebhookEndpoint.register(
            UUID.randomUUID(), "https://example.com", null, List.of("x"), "old-secret");
    WebhookEndpoint rotated = endpoint.rotateSecret("new-secret", Duration.ofHours(24));

    repository.save(rotated);
    WebhookEndpoint found = repository.findById(endpoint.id()).orElseThrow();

    assertThat(found.currentSecretEncrypted()).isEqualTo("new-secret");
    assertThat(found.previousSecretEncrypted()).isEqualTo("old-secret");
    assertThat(found.previousSecretExpiresAt()).isNotNull();
  }

  @Test
  void findAllByOrganizationIdReturnsOnlyThatOrganizationsEndpoints() {
    UUID organizationA = UUID.randomUUID();
    UUID organizationB = UUID.randomUUID();
    WebhookEndpoint ownedByA =
        WebhookEndpoint.register(organizationA, "https://a.example.com", null, List.of("x"), "s");
    WebhookEndpoint ownedByB =
        WebhookEndpoint.register(organizationB, "https://b.example.com", null, List.of("x"), "s");
    repository.save(ownedByA);
    repository.save(ownedByB);

    List<WebhookEndpoint> found = repository.findAllByOrganizationId(organizationA);

    assertThat(found).extracting(WebhookEndpoint::id).containsExactly(ownedByA.id());
  }

  @Test
  void findActiveByOrganizationIdAndEventTypeExcludesInactiveAndUnsubscribedEndpoints() {
    UUID organizationId = UUID.randomUUID();
    WebhookEndpoint subscribedActive =
        WebhookEndpoint.register(
            organizationId, "https://active.example.com", null, List.of("account.created"), "s");
    WebhookEndpoint subscribedInactive =
        WebhookEndpoint.register(
                organizationId,
                "https://inactive.example.com",
                null,
                List.of("account.created"),
                "s")
            .deactivate();
    WebhookEndpoint activeButNotSubscribed =
        WebhookEndpoint.register(
            organizationId, "https://other.example.com", null, List.of("account.deleted"), "s");
    repository.save(subscribedActive);
    repository.save(subscribedInactive);
    repository.save(activeButNotSubscribed);

    List<WebhookEndpoint> matching =
        repository.findActiveByOrganizationIdAndEventType(organizationId, "account.created");

    assertThat(matching).extracting(WebhookEndpoint::id).containsExactly(subscribedActive.id());
  }

  @Test
  void findActiveByOrganizationIdExcludesInactiveButNotByEventTypeSubscription() {
    // TD-PERF-005: unlike findActiveByOrganizationIdAndEventType above, this one deliberately
    // returns every active endpoint regardless of what it subscribes to —
    // DispatchOutboxEventsService
    // filters by event type itself, in memory, so it can reuse one call's own result across every
    // event from the same Organization in a batch.
    UUID organizationId = UUID.randomUUID();
    WebhookEndpoint active =
        WebhookEndpoint.register(
            organizationId, "https://active.example.com", null, List.of("account.created"), "s");
    WebhookEndpoint inactive =
        WebhookEndpoint.register(
                organizationId,
                "https://inactive.example.com",
                null,
                List.of("account.created"),
                "s")
            .deactivate();
    WebhookEndpoint activeSubscribedToSomethingElse =
        WebhookEndpoint.register(
            organizationId, "https://other.example.com", null, List.of("account.deleted"), "s");
    repository.save(active);
    repository.save(inactive);
    repository.save(activeSubscribedToSomethingElse);

    List<WebhookEndpoint> found = repository.findActiveByOrganizationId(organizationId);

    assertThat(found)
        .extracting(WebhookEndpoint::id)
        .containsExactlyInAnyOrder(active.id(), activeSubscribedToSomethingElse.id());
  }

  @Test
  void findByIdReturnsEmptyForAnUnknownId() {
    assertThat(repository.findById(UUID.randomUUID())).isEmpty();
  }

  @Configuration
  @EnableAutoConfiguration
  @EnableJpaRepositories(basePackageClasses = SpringDataWebhookEndpointJpaRepository.class)
  @Import(JpaWebhookEndpointRepository.class)
  static class TestConfig {}
}
