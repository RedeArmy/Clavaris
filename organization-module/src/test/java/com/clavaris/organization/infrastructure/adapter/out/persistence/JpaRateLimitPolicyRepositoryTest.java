package com.clavaris.organization.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.application.usecase.setratelimitpolicyfororganization.RateLimitPolicyRepository;
import com.clavaris.organization.domain.model.Organization;
import com.clavaris.organization.domain.model.RateLimitPolicy;
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
 * ADR-0010 §6.2: real-Postgres integration test — same rationale/pattern as
 * JpaOrganizationRepositoryTest's own Javadoc. A real {@code organizations} row is required before
 * any {@code RateLimitPolicy} save: {@code rate_limit_policies.organization_id} is a real FK
 * (V20260822110000's own comment — both tables are this module's own migrations, so their ordering
 * is guaranteed), unlike the cross-module columns elsewhere in this codebase that deliberately omit
 * one.
 */
@SpringBootTest(classes = JpaRateLimitPolicyRepositoryTest.TestConfig.class)
@Testcontainers
class JpaRateLimitPolicyRepositoryTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Autowired private OrganizationRepository organizations;
  @Autowired private RateLimitPolicyRepository policies;
  @Autowired private SpringDataRateLimitPolicyJpaRepository springDataRepository;

  @Test
  void savesAPolicyAndPersistsItsRealFields() {
    UUID organizationId = registerARealOrganization();
    RateLimitPolicy policy = RateLimitPolicy.define(organizationId, 500, 6000);

    policies.save(policy);

    RateLimitPolicyEntity persisted = springDataRepository.findById(policy.id()).orElseThrow();
    assertThat(persisted.getOrganizationId()).isEqualTo(organizationId);
    assertThat(persisted.getRequestsPerMinute()).isEqualTo(500);
    // Postgres' timestamptz column stores microsecond precision, not the nanosecond precision
    // Instant.now() carries in memory — an exact isEqualTo would be a coin-flip on every real run.
    assertThat(persisted.getCreatedAt())
        .isCloseTo(policy.createdAt(), within(1, ChronoUnit.MILLIS));
  }

  @Test
  void findByOrganizationIdReturnsEmptyWhenNoPolicyHasEverBeenSetForThatOrganization() {
    UUID organizationId = registerARealOrganization();

    Optional<RateLimitPolicy> found = policies.findByOrganizationId(organizationId);

    assertThat(found)
        .as("absence must mean \"use the system default\" (ADR-0010 §6.2), not an error")
        .isEmpty();
  }

  @Test
  void savingASecondTimeUpdatesTheSameRowRatherThanInsertingASecondOne() {
    UUID organizationId = registerARealOrganization();
    RateLimitPolicy original = RateLimitPolicy.define(organizationId, 500, 6000);
    policies.save(original);

    RateLimitPolicy updated = original.withRequestsPerMinute(900, 6000);
    policies.save(updated);

    assertThat(springDataRepository.count())
        .as("one Organization must never accumulate two policy rows")
        .isEqualTo(1);
    RateLimitPolicy found = policies.findByOrganizationId(organizationId).orElseThrow();
    assertThat(found.requestsPerMinute()).isEqualTo(900);
  }

  private UUID registerARealOrganization() {
    Organization organization = Organization.register("Rate Limit Co", UUID.randomUUID());
    organizations.save(organization);
    return organization.id();
  }

  @Configuration
  @EnableAutoConfiguration
  @EnableJpaRepositories(
      basePackageClasses = {
        SpringDataOrganizationJpaRepository.class,
        SpringDataRateLimitPolicyJpaRepository.class
      })
  @Import({JpaOrganizationRepository.class, JpaRateLimitPolicyRepository.class})
  static class TestConfig {}
}
