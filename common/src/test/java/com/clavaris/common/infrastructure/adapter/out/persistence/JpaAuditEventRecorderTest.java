package com.clavaris.common.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
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

/**
 * TD-SEC-007: real-Postgres integration test — same rationale/pattern as every other module's own
 * persistence-adapter test (e.g. {@code JpaRateLimitPolicyRepositoryTest}). Runs the actual {@code
 * audit_events} migration, not a Hibernate-generated schema.
 *
 * <p>{@code @Transactional} (same convention as {@code JpaPlatformAccountRepositoryTest}): each
 * test method's writes roll back at the end of that method, so {@code findAll()}/{@code count()}
 * assertions below see only that test's own rows, not ones left over by an earlier test sharing the
 * same cached Spring context/container.
 */
@SpringBootTest(classes = JpaAuditEventRecorderTest.TestConfig.class)
@Testcontainers
@Transactional
class JpaAuditEventRecorderTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Autowired private AuditEventRecorder recorder;
  @Autowired private SpringDataAuditEventJpaRepository springDataRepository;

  @Test
  void recordPersistsEveryFieldOfAPlatformAccountActedEvent() {
    UUID platformAccountId = UUID.randomUUID();

    recorder.record(
        AuditActor.platformAccount(platformAccountId),
        "organization.created",
        "Organization",
        "org-1",
        "name=Acme");

    List<AuditEventEntity> rows = springDataRepository.findAll();
    assertThat(rows).hasSize(1);
    AuditEventEntity persisted = rows.get(0);
    assertThat(persisted.getActorType()).isEqualTo("PLATFORM_ACCOUNT");
    assertThat(persisted.getActorId()).isEqualTo(platformAccountId.toString());
    assertThat(persisted.getAction()).isEqualTo("organization.created");
    assertThat(persisted.getTargetType()).isEqualTo("Organization");
    assertThat(persisted.getTargetId()).isEqualTo("org-1");
    assertThat(persisted.getDetail()).isEqualTo("name=Acme");
    assertThat(persisted.getOccurredAt()).isNotNull();
  }

  @Test
  void recordPersistsAPlatformClientActedEventWithANullTargetIdAndDetail() {
    recorder.record(
        AuditActor.platformClient("bootstrap-client"),
        "rate_limit_policy.set",
        "RateLimitPolicy",
        null,
        null);

    AuditEventEntity persisted = springDataRepository.findAll().get(0);
    assertThat(persisted.getActorType()).isEqualTo("PLATFORM_CLIENT");
    assertThat(persisted.getActorId()).isEqualTo("bootstrap-client");
    assertThat(persisted.getTargetId()).isNull();
    assertThat(persisted.getDetail()).isNull();
  }

  @Test
  void everyCallAppendsARowRatherThanUpdatingAPreviousOne() {
    AuditActor actor = AuditActor.platformAccount(UUID.randomUUID());

    recorder.record(actor, "organization.created", "Organization", "org-1", null);
    recorder.record(
        actor, "rate_limit_policy.set", "RateLimitPolicy", "org-1", "requestsPerMinute=800");

    assertThat(springDataRepository.count())
        .as("an audit trail is append-only — two distinct actions must produce two distinct rows")
        .isEqualTo(2);
  }

  @Configuration
  @EnableAutoConfiguration
  @EnableJpaRepositories(basePackageClasses = SpringDataAuditEventJpaRepository.class)
  @Import(JpaAuditEventRecorder.class)
  static class TestConfig {}
}
