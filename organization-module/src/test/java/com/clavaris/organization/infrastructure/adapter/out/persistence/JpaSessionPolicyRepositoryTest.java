package com.clavaris.organization.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.application.usecase.setsessionpolicyfororganization.SessionPolicyRepository;
import com.clavaris.organization.domain.model.Organization;
import com.clavaris.organization.domain.model.SessionPolicy;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
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
 * Real-Postgres integration test — same rationale/pattern as {@code
 * JpaRateLimitPolicyRepositoryTest}'s own Javadoc. A real {@code organizations} row is required
 * before any {@code SessionPolicy} save: {@code session_policies.organization_id} is a real FK,
 * created in this same module's own migration sequence.
 */
@SpringBootTest(classes = JpaSessionPolicyRepositoryTest.TestConfig.class)
@Testcontainers
class JpaSessionPolicyRepositoryTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Autowired private OrganizationRepository organizations;
  @Autowired private SessionPolicyRepository policies;
  @Autowired private SpringDataSessionPolicyJpaRepository springDataRepository;

  @Test
  void savesAPolicyAndPersistsItsRealFields() {
    UUID organizationId = registerARealOrganization();
    SessionPolicy policy = SessionPolicy.define(organizationId, 20_160, 1_440, 5, false);

    policies.save(policy);

    SessionPolicyEntity persisted = springDataRepository.findById(policy.id()).orElseThrow();
    assertThat(persisted.getOrganizationId()).isEqualTo(organizationId);
    assertThat(persisted.getMaximumLifetimeMinutes()).isEqualTo(20_160);
    assertThat(persisted.getInactivityTimeoutMinutes()).isEqualTo(1_440);
    assertThat(persisted.getReverificationWindowMinutes()).isEqualTo(5);
    assertThat(persisted.isMultiSessionHandlingEnabled()).isFalse();
    // Postgres' timestamptz column stores microsecond precision, not the nanosecond precision
    // Instant.now() carries in memory — an exact isEqualTo would be a coin-flip on every real run.
    assertThat(persisted.getCreatedAt())
        .isCloseTo(policy.createdAt(), within(1, ChronoUnit.MILLIS));
  }

  @Test
  void findByOrganizationIdReturnsEmptyWhenNoPolicyHasEverBeenSetForThatOrganization() {
    UUID organizationId = registerARealOrganization();

    Optional<SessionPolicy> found = policies.findByOrganizationId(organizationId);

    assertThat(found).as("absence must mean \"use the system default\", not an error").isEmpty();
  }

  @Test
  void savingASecondTimeUpdatesTheSameRowRatherThanInsertingASecondOne() {
    UUID organizationId = registerARealOrganization();
    SessionPolicy original = SessionPolicy.define(organizationId, 10_080, 10_080, 10, true);
    policies.save(original);

    SessionPolicy updated = original.withPolicy(20_160, 1_440, 5, false);
    policies.save(updated);

    assertThat(springDataRepository.count())
        .as("one Organization must never accumulate two policy rows")
        .isEqualTo(1);
    SessionPolicy found = policies.findByOrganizationId(organizationId).orElseThrow();
    assertThat(found.maximumLifetimeMinutes()).isEqualTo(20_160);
  }

  private UUID registerARealOrganization() {
    Organization organization = Organization.register("Session Policy Co", UUID.randomUUID());
    organizations.save(organization);
    return organization.id();
  }

  @Configuration
  @EnableAutoConfiguration
  @EnableJpaRepositories(
      basePackageClasses = {
        SpringDataOrganizationJpaRepository.class,
        SpringDataSessionPolicyJpaRepository.class
      })
  @Import({JpaOrganizationRepository.class, JpaSessionPolicyRepository.class})
  static class TestConfig {}
}
