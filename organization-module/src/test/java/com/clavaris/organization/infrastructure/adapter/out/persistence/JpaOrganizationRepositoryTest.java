package com.clavaris.organization.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.within;

import com.clavaris.common.domain.model.Page;
import com.clavaris.common.domain.model.PageRequest;
import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.domain.model.Organization;
import com.clavaris.organization.domain.model.OrganizationEnvironment;
import java.time.Instant;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
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

  @Autowired private JdbcTemplate jdbcTemplate;

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

  // SDE-III feature build, 2026-09-04 (Clerk Development/Production instances analysis).
  @Test
  void savesAndReconstitutesTheEnvironmentAndLinkedEnvironmentOrganizationId() {
    UUID ownerPlatformAccountId = UUID.randomUUID();
    Organization developmentOrganization =
        Organization.register("JobSeeker", ownerPlatformAccountId);
    Organization productionOrganization =
        Organization.registerProductionEnvironment(
            "JobSeeker (production)", ownerPlatformAccountId, developmentOrganization.id());
    repository.save(developmentOrganization);
    repository.save(productionOrganization);
    repository.save(
        developmentOrganization.withLinkedEnvironmentOrganizationId(productionOrganization.id()));

    Organization reloadedDevelopment =
        repository.findById(developmentOrganization.id()).orElseThrow();
    Organization reloadedProduction =
        repository.findById(productionOrganization.id()).orElseThrow();

    assertThat(reloadedDevelopment.environment()).isEqualTo(OrganizationEnvironment.DEVELOPMENT);
    assertThat(reloadedDevelopment.linkedEnvironmentOrganizationId())
        .contains(productionOrganization.id());
    assertThat(reloadedProduction.environment()).isEqualTo(OrganizationEnvironment.PRODUCTION);
    assertThat(reloadedProduction.linkedEnvironmentOrganizationId())
        .contains(developmentOrganization.id());
  }

  // Every Organization that already existed before this concept shipped must default to
  // PRODUCTION at the database level (migration V20260904090000) — a raw INSERT that never
  // mentions the column at all, the only way to actually exercise the SQL DEFAULT clause itself
  // rather than a value this entity's own constructor happened to pass through.
  @Test
  void anOrganizationInsertedWithoutAnExplicitEnvironmentDefaultsToProductionAtTheDatabaseLevel() {
    UUID organizationId = UUID.randomUUID();
    jdbcTemplate.update(
        "insert into organizations (id, name, owner_platform_account_id) values (?, ?, ?)",
        organizationId,
        "Pre-existing Co",
        UUID.randomUUID());

    Organization reloaded = repository.findById(organizationId).orElseThrow();

    assertThat(reloaded.environment()).isEqualTo(OrganizationEnvironment.PRODUCTION);
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

  // TD-PERF-020: real-Postgres proof of the paginated sibling — page size, total element count,
  // and newest-first ordering all come from the actual query, not assumed from the mapping code.
  // Built via Organization.reconstitute with explicit, deliberately-spaced createdAt instants
  // (not three real-time Organization.register calls a few microseconds apart) — same "don't rely
  // on a real wall-clock gap to prove an ordering" discipline JpaAuditEventReaderTest's own
  // returnsNewestFirst rewrite already established, avoiding a SonarCloud/S2925-flagged
  // Thread.sleep and, more importantly, avoiding a genuinely flaky assertion on a busy machine.
  @Test
  void findPageOwnedByReturnsOnePageAtATimeNewestFirst() {
    UUID owner = UUID.randomUUID();
    Instant now = Instant.now();
    Organization first = reconstituteAt(owner, "First Created", now.minusSeconds(20));
    Organization second = reconstituteAt(owner, "Second Created", now.minusSeconds(10));
    Organization third = reconstituteAt(owner, "Third Created", now);
    repository.save(first);
    repository.save(second);
    repository.save(third);
    // A different owner's own Organization must never leak into this owner's own page.
    repository.save(Organization.register("Someone Else's Org", UUID.randomUUID()));

    Page<Organization> firstPage = repository.findPageOwnedBy(owner, new PageRequest(0, 2));

    assertThat(firstPage.content())
        .extracting(Organization::id)
        .containsExactly(third.id(), second.id());
    assertThat(firstPage.totalElements()).isEqualTo(3);
    assertThat(firstPage.totalPages()).isEqualTo(2);
    assertThat(firstPage.hasNext()).isTrue();
    assertThat(firstPage.hasPrevious()).isFalse();

    Page<Organization> secondPage = repository.findPageOwnedBy(owner, new PageRequest(1, 2));

    assertThat(secondPage.content()).extracting(Organization::id).containsExactly(first.id());
    assertThat(secondPage.hasNext()).isFalse();
    assertThat(secondPage.hasPrevious()).isTrue();
  }

  private static Organization reconstituteAt(
      final UUID owner, final String name, final Instant createdAt) {
    return Organization.reconstitute(
        UUID.randomUUID(),
        name,
        createdAt,
        owner,
        false,
        List.of(),
        OrganizationEnvironment.DEVELOPMENT,
        null);
  }

  @Test
  void findPageOwnedByReturnsAnEmptyPageForAnOwnerWithNoOrganizations() {
    Page<Organization> page = repository.findPageOwnedBy(UUID.randomUUID(), PageRequest.first());

    assertThat(page.isEmpty()).isTrue();
    assertThat(page.totalElements()).isZero();
    assertThat(page.totalPages()).isZero();
  }

  // TD-PERF-019: proves insert() genuinely uses persist() semantics (fails loudly on a duplicate
  // id), not merge()'s silent-update behavior — same load-bearing regression proof
  // JpaAccountRepositoryTest's own identical pair of tests already established for Account.
  @Test
  void insertPersistsANewOrganizationFindableAfterward() {
    Organization organization = Organization.register("Inserted Co", UUID.randomUUID());

    repository.insert(organization);

    Organization found = repository.findById(organization.id()).orElseThrow();
    assertThat(found.id()).isEqualTo(organization.id());
    assertThat(found.name()).isEqualTo("Inserted Co");
  }

  @Test
  void insertOnAnAlreadyPersistedIdFailsLoudlyInsteadOfSilentlyUpdating() {
    Organization original = Organization.register("Original Co", UUID.randomUUID());
    repository.insert(original);
    Organization reusesTheSameId =
        Organization.reconstitute(
            original.id(),
            "A Different Name",
            original.createdAt(),
            original.ownerPlatformAccountId(),
            original.socialLoginEnabled(),
            original.allowedSocialProviders(),
            original.environment(),
            original.linkedEnvironmentOrganizationId().orElse(null));

    assertThatExceptionOfType(DataIntegrityViolationException.class)
        .isThrownBy(() -> repository.insert(reusesTheSameId));
  }

  @Configuration
  @EnableAutoConfiguration
  @EnableJpaRepositories(basePackageClasses = SpringDataOrganizationJpaRepository.class)
  @Import(JpaOrganizationRepository.class)
  static class TestConfig {}
}
