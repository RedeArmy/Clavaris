package com.clavaris.common.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.common.application.port.AuditEventReader;
import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.common.domain.model.AuditEvent;
import com.clavaris.common.domain.model.AuditEventTargetRef;
import com.clavaris.common.domain.model.KeysetCursor;
import java.time.Instant;
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
  @Autowired private SpringDataAuditEventJpaRepository springDataRepository;

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
  void returnsNewestFirst() {
    // Two rows inserted directly via the Spring Data repository, not AuditEventRecorder — its own
    // write() always stamps occurredAt as Instant.now(), no injectable clock, so proving ordering
    // through it would need a real wall-clock gap (a Thread.sleep, flagged by SonarCloud/S2925 as
    // exactly the kind of flaky-by-construction test it is). Fixed, far-apart timestamps here are
    // deterministic and instant.
    UUID organizationId = UUID.randomUUID();
    Instant earlier = Instant.now().minusSeconds(60);
    Instant later = Instant.now();
    springDataRepository.save(
        new AuditEventEntity(
            UUID.randomUUID(),
            "PLATFORM_ACCOUNT",
            UUID.randomUUID().toString(),
            "organization.created",
            "Organization",
            organizationId.toString(),
            "first",
            earlier,
            null));
    springDataRepository.save(
        new AuditEventEntity(
            UUID.randomUUID(),
            "PLATFORM_ACCOUNT",
            UUID.randomUUID().toString(),
            "rate_limit_policy.set",
            "Organization",
            organizationId.toString(),
            "second",
            later,
            null));

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

  // TD-ARCH-038: the tenant-keyed page — scoped to one Organization, newest first, resumable.
  private void saveEvent(final UUID organizationId, final String detail, final Instant at) {
    springDataRepository.save(
        new AuditEventEntity(
            UUID.randomUUID(),
            "PLATFORM_ACCOUNT",
            UUID.randomUUID().toString(),
            "organization.updated",
            "Organization",
            organizationId == null ? null : organizationId.toString(),
            detail,
            at,
            organizationId));
  }

  @Test
  void anOrganizationsPageHoldsOnlyItsOwnEventsNewestFirst() {
    final UUID mine = UUID.randomUUID();
    final UUID theirs = UUID.randomUUID();
    final Instant base = Instant.parse("2026-10-25T12:00:00Z");
    saveEvent(mine, "oldest", base.minusSeconds(120));
    saveEvent(theirs, "someone else's", base.minusSeconds(60));
    saveEvent(mine, "newest", base);
    saveEvent(null, "no organization", base.minusSeconds(30));

    final List<AuditEvent> page = reader.findPageForOrganization(mine, null, 10);

    assertThat(page)
        .extracting(event -> event.detail().orElse(""))
        .containsExactly("newest", "oldest");
  }

  @Test
  void aCursorResumesAfterTheLastRowOfThePreviousPageWithoutRepeatsOrGaps() {
    final UUID mine = UUID.randomUUID();
    final Instant base = Instant.parse("2026-10-25T12:00:00Z");
    for (int i = 0; i < 5; i++) {
      saveEvent(mine, "event-" + i, base.minusSeconds(i));
    }

    final List<AuditEvent> first = reader.findPageForOrganization(mine, null, 2);
    final AuditEvent lastOfFirst = first.getLast();
    final List<AuditEvent> second =
        reader.findPageForOrganization(
            mine, new KeysetCursor(lastOfFirst.occurredAt(), lastOfFirst.id()), 2);

    assertThat(first)
        .extracting(event -> event.detail().orElse(""))
        .containsExactly("event-0", "event-1");
    assertThat(second)
        .extracting(event -> event.detail().orElse(""))
        .containsExactly("event-2", "event-3");
  }

  @Test
  void rowsRecordedInTheSameInstantAreOrderedByIdSoNoneIsSkippedAtAPageBoundary() {
    final UUID mine = UUID.randomUUID();
    final Instant sameInstant = Instant.parse("2026-10-25T12:00:00Z");
    for (int i = 0; i < 4; i++) {
      saveEvent(mine, "tie-" + i, sameInstant);
    }

    final List<AuditEvent> first = reader.findPageForOrganization(mine, null, 2);
    final List<AuditEvent> second =
        reader.findPageForOrganization(
            mine, new KeysetCursor(first.getLast().occurredAt(), first.getLast().id()), 2);

    assertThat(java.util.stream.Stream.concat(first.stream(), second.stream()).map(AuditEvent::id))
        .doesNotHaveDuplicates()
        .hasSize(4);
  }
}
