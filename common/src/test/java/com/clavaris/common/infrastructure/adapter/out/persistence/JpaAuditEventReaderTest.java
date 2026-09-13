package com.clavaris.common.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.common.application.port.AuditEventReader;
import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.common.domain.model.AuditEvent;
import com.clavaris.common.domain.model.AuditEventTargetRef;
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
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real-Postgres integration test — same rationale/pattern as {@link JpaAuditEventRecorderTest}. */
@SpringBootTest(classes = JpaAuditEventReaderTest.TestConfig.class)
@Testcontainers
@Transactional
class JpaAuditEventReaderTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Autowired private AuditEventRecorder recorder;
  @Autowired private AuditEventReader reader;

  @Test
  void returnsOnlyRowsMatchingAnyOfTheGivenTargetRefs() {
    UUID organizationId = UUID.randomUUID();
    UUID otherOrganizationId = UUID.randomUUID();
    AuditActor actor = AuditActor.platformAccount(UUID.randomUUID());

    recorder.write(actor, "organization.created", "Organization", organizationId.toString(), null);
    recorder.write(
        actor,
        "organization.created",
        "Organization",
        otherOrganizationId.toString(),
        "a different Organization's own row — must never match");

    List<AuditEvent> result =
        reader.findRecentForTargets(
            List.of(new AuditEventTargetRef("Organization", organizationId.toString())), 100);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).targetId()).contains(organizationId.toString());
  }

  @Test
  void matchesAcrossSeveralDistinctTargetTypesInOneCall() {
    UUID organizationId = UUID.randomUUID();
    UUID workspaceId = UUID.randomUUID();
    AuditActor actor = AuditActor.platformAccount(UUID.randomUUID());

    recorder.write(actor, "organization.created", "Organization", organizationId.toString(), null);
    recorder.write(actor, "workspace.created", "Workspace", workspaceId.toString(), null);
    recorder.write(
        actor,
        "webhook_endpoint.registered",
        "WebhookEndpoint",
        UUID.randomUUID().toString(),
        "a WebhookEndpoint this query never asked about — must never match");

    List<AuditEvent> result =
        reader.findRecentForTargets(
            List.of(
                new AuditEventTargetRef("Organization", organizationId.toString()),
                new AuditEventTargetRef("Workspace", workspaceId.toString())),
            100);

    assertThat(result).hasSize(2);
    assertThat(result)
        .extracting(AuditEvent::action)
        .containsExactlyInAnyOrder("organization.created", "workspace.created");
  }

  @Test
  void returnsNewestFirst() throws InterruptedException {
    UUID organizationId = UUID.randomUUID();
    AuditActor actor = AuditActor.platformAccount(UUID.randomUUID());

    recorder.write(
        actor, "organization.created", "Organization", organizationId.toString(), "first");
    // A real, if small, wall-clock gap — occurredAt is Instant.now() with no artificial clock
    // injected, so two calls in the same millisecond would otherwise make this test flaky.
    Thread.sleep(5);
    recorder.write(
        actor, "rate_limit_policy.set", "Organization", organizationId.toString(), "second");

    List<AuditEvent> result =
        reader.findRecentForTargets(
            List.of(new AuditEventTargetRef("Organization", organizationId.toString())), 100);

    assertThat(result)
        .extracting(AuditEvent::action)
        .containsExactly("rate_limit_policy.set", "organization.created");
  }

  @Test
  void returnsNothingWhenNoTargetsAreGiven() {
    List<AuditEvent> result = reader.findRecentForTargets(List.of(), 100);

    assertThat(result).isEmpty();
  }

  @Test
  void honorsTheMaxResultsCap() {
    UUID organizationId = UUID.randomUUID();
    AuditActor actor = AuditActor.platformAccount(UUID.randomUUID());
    for (int i = 0; i < 5; i++) {
      recorder.write(
          actor, "rate_limit_policy.set", "Organization", organizationId.toString(), "n=" + i);
    }

    List<AuditEvent> result =
        reader.findRecentForTargets(
            List.of(new AuditEventTargetRef("Organization", organizationId.toString())), 3);

    assertThat(result).hasSize(3);
  }

  @Configuration
  @EnableAutoConfiguration
  @EnableJpaRepositories(basePackageClasses = SpringDataAuditEventJpaRepository.class)
  @Import({JpaAuditEventRecorder.class, JpaAuditEventReader.class})
  static class TestConfig {}
}
