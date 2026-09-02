package com.clavaris.webhook.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.webhook.application.usecase.deliverpendingwebhooks.WebhookDeliveryRepository;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.WebhookEndpointRepository;
import com.clavaris.webhook.domain.model.WebhookDelivery;
import com.clavaris.webhook.domain.model.WebhookEndpoint;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real-Postgres integration test for the WebhookDelivery persistence adapter (migration {@code
 * V20260902120001}) — {@code claimDueBatch}'s own {@code SELECT ... FOR UPDATE SKIP LOCKED}
 * behavior is only provable against a real database, never a mocked port; a plain unit test can
 * assert the SQL string but not that Postgres actually honours it under real concurrency.
 *
 * <p>{@code webhook_deliveries.endpoint_id} has a real FK to {@code webhook_endpoints} (this
 * table's own migration) — every delivery below is attached to a real, persisted {@link
 * WebhookEndpoint} row, never a bare random {@link UUID}, or the insert itself would fail the FK
 * constraint before this test could observe anything.
 */
@SpringBootTest(classes = JpaWebhookDeliveryRepositoryTest.TestConfig.class)
@Testcontainers
class JpaWebhookDeliveryRepositoryTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Autowired private WebhookDeliveryRepository repository;
  @Autowired private WebhookEndpointRepository endpoints;
  @Autowired private PlatformTransactionManager transactionManager;

  @Test
  void savesAndReadsBackADelivery_reconstituteKeepsTheRealPersistedFields() {
    WebhookDelivery delivery = scheduledDelivery();

    repository.save(delivery);
    WebhookDelivery found = repository.findById(delivery.id()).orElseThrow();

    assertThat(found.id()).isEqualTo(delivery.id());
    assertThat(found.endpointId()).isEqualTo(delivery.endpointId());
    assertThat(found.organizationId()).isEqualTo(delivery.organizationId());
    assertThat(found.outboxEventId()).isEqualTo(delivery.outboxEventId());
    assertThat(found.eventType()).isEqualTo("account.created");
    assertThat(found.status()).isEqualTo(delivery.status());
  }

  @Test
  void claimDueBatchReturnsAPendingRow() {
    WebhookDelivery pending = scheduledDelivery();
    repository.save(pending);

    List<WebhookDelivery> claimed = repository.claimDueBatch(10);

    assertThat(claimed).extracting(WebhookDelivery::id).contains(pending.id());
  }

  @Test
  void claimDueBatchReturnsAFailedRowOnlyOnceItsNextAttemptIsDue() {
    WebhookDelivery dueNow =
        scheduledDelivery()
            .recordFailure(500, "boom", Instant.now(), Instant.now().minusSeconds(1));
    WebhookDelivery dueLater =
        scheduledDelivery()
            .recordFailure(500, "boom", Instant.now(), Instant.now().plusSeconds(3600));
    repository.save(dueNow);
    repository.save(dueLater);

    List<WebhookDelivery> claimed = repository.claimDueBatch(10);

    assertThat(claimed)
        .extracting(WebhookDelivery::id)
        .contains(dueNow.id())
        .doesNotContain(dueLater.id());
  }

  @Test
  void claimDueBatchNeverReturnsTerminalRows() {
    // A pending row alongside the two terminal ones — otherwise an empty claimed list would pass
    // this test vacuously (java:S5838) whether or not the terminal-row filter is actually doing
    // anything, since a genuinely broken query would also return nothing.
    WebhookDelivery pending = scheduledDelivery();
    WebhookDelivery succeeded = scheduledDelivery().recordSuccess(200, Instant.now());
    WebhookDelivery exhausted = scheduledDelivery().recordFailure(500, "boom", Instant.now(), null);
    repository.save(pending);
    repository.save(succeeded);
    repository.save(exhausted);

    List<WebhookDelivery> claimed = repository.claimDueBatch(10);

    assertThat(claimed)
        .extracting(WebhookDelivery::id)
        .contains(pending.id())
        .doesNotContain(succeeded.id(), exhausted.id());
  }

  @Test
  void claimDueBatchLeasesEveryClaimedRowIntoTheNearFuture() {
    WebhookDelivery pending = scheduledDelivery();
    repository.save(pending);
    Instant beforeClaim = Instant.now();

    repository.claimDueBatch(10);

    WebhookDelivery afterClaim = repository.findById(pending.id()).orElseThrow();
    assertThat(afterClaim.nextAttemptAt()).isAfter(beforeClaim.plusSeconds(60));
  }

  @Test
  void claimDueBatchRespectsTheLimit() {
    for (int i = 0; i < 5; i++) {
      repository.save(scheduledDelivery());
    }

    List<WebhookDelivery> claimed = repository.claimDueBatch(3);

    assertThat(claimed).hasSize(3);
  }

  @Test
  void concurrentClaimsNeverReturnTheSameRow_skipLockedProofUnderRealConcurrency() {
    for (int i = 0; i < 20; i++) {
      repository.save(scheduledDelivery());
    }
    TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

    CompletableFuture<List<UUID>> claimA =
        CompletableFuture.supplyAsync(
            () ->
                transactionTemplate.execute(
                    status ->
                        repository.claimDueBatch(10).stream().map(WebhookDelivery::id).toList()));
    CompletableFuture<List<UUID>> claimB =
        CompletableFuture.supplyAsync(
            () ->
                transactionTemplate.execute(
                    status ->
                        repository.claimDueBatch(10).stream().map(WebhookDelivery::id).toList()));

    List<UUID> idsFromA = claimA.join();
    List<UUID> idsFromB = claimB.join();

    // java:S5838: a non-vacuous check that either side actually claimed something, before
    // asserting the two sets are disjoint — otherwise two empty lists would trivially satisfy
    // doesNotContainAnyElementsOf without proving SKIP LOCKED ever actually split the work.
    assertThat(idsFromA.size() + idsFromB.size()).isGreaterThan(0);
    assertThat(idsFromA).doesNotContainAnyElementsOf(idsFromB);
  }

  private WebhookDelivery scheduledDelivery() {
    return WebhookDelivery.schedule(
        newPersistedEndpointId(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "Account",
        UUID.randomUUID(),
        "account.created",
        "{}");
  }

  private UUID newPersistedEndpointId() {
    WebhookEndpoint endpoint =
        WebhookEndpoint.register(
            UUID.randomUUID(), "https://example.com/hook", null, List.of("account.created"), "s");
    endpoints.save(endpoint);
    return endpoint.id();
  }

  @Configuration
  @EnableAutoConfiguration
  @EnableJpaRepositories(
      basePackageClasses = {
        SpringDataWebhookDeliveryJpaRepository.class,
        SpringDataWebhookEndpointJpaRepository.class
      })
  @Import({JpaWebhookDeliveryRepository.class, JpaWebhookEndpointRepository.class})
  static class TestConfig {}
}
