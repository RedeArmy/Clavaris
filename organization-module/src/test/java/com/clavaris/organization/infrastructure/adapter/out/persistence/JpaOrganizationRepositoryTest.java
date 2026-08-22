package com.clavaris.organization.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.domain.model.Organization;
import java.time.temporal.ChronoUnit;
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
 * test-strategy.md §2: a real-Postgres integration test for the Organization persistence adapter —
 * proves the entity mapping round-trips correctly against the actual {@code organizations}
 * migration, not a Hibernate-generated schema.
 *
 * <p>Deliberately NOT {@code @DataJpaTest}: confirmed live (client-registry-module's own
 * JpaPlatformClientRepositoryTest, identity-module's JpaSigningKeyRepositoryTest) that Spring Boot
 * 4.1 no longer has that test slice at all. Same hand-assembled {@code @SpringBootTest} pattern
 * here — {@code @Import}, not {@code @ComponentScan}, for the same reason documented on those
 * tests' own {@code TestConfig} (avoids picking up a sibling test's nested {@code @Configuration}
 * if one is ever added to this package later).
 */
@SpringBootTest(classes = JpaOrganizationRepositoryTest.TestConfig.class)
@Testcontainers
class JpaOrganizationRepositoryTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Autowired private OrganizationRepository repository;

  @Autowired private SpringDataOrganizationJpaRepository springDataRepository;

  @Test
  void savesAnOrganizationAndPersistsItsRealFields() {
    UUID ownerPlatformAccountId = UUID.randomUUID();
    Organization organization = Organization.register("JobSeeker", ownerPlatformAccountId);

    repository.save(organization);

    OrganizationEntity persisted = springDataRepository.findById(organization.id()).orElseThrow();
    assertThat(persisted.getId()).isEqualTo(organization.id());
    assertThat(persisted.getName()).isEqualTo("JobSeeker");
    assertThat(persisted.getOwnerPlatformAccountId()).isEqualTo(ownerPlatformAccountId);
    // Postgres' timestamptz column stores microsecond precision, not the nanosecond precision
    // Instant.now() carries in memory — an exact isEqualTo would be a coin-flip on every real
    // run, not a genuine assertion.
    assertThat(persisted.getCreatedAt())
        .isCloseTo(organization.createdAt(), within(1, ChronoUnit.MILLIS));
  }

  @Test
  void findAllOwnedByReturnsOnlyThatOwnersOrganizations() {
    UUID ownerA = UUID.randomUUID();
    UUID ownerB = UUID.randomUUID();
    Organization ownedByA1 = Organization.register("A's First Org", ownerA);
    Organization ownedByA2 = Organization.register("A's Second Org", ownerA);
    Organization ownedByB = Organization.register("B's Org", ownerB);
    repository.save(ownedByA1);
    repository.save(ownedByA2);
    repository.save(ownedByB);

    List<Organization> found = repository.findAllOwnedBy(ownerA);

    assertThat(found)
        .extracting(Organization::id)
        .containsExactlyInAnyOrder(ownedByA1.id(), ownedByA2.id());
  }

  @Configuration
  @EnableAutoConfiguration
  @EnableJpaRepositories(basePackageClasses = SpringDataOrganizationJpaRepository.class)
  @Import(JpaOrganizationRepository.class)
  static class TestConfig {}
}
