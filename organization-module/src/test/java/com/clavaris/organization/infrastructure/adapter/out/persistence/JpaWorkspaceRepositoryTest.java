package com.clavaris.organization.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.clavaris.common.domain.model.Page;
import com.clavaris.common.domain.model.PageRequest;
import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.application.usecase.createworkspace.WorkspaceRepository;
import com.clavaris.organization.domain.model.Organization;
import com.clavaris.organization.domain.model.Workspace;
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
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real-Postgres integration test for the Workspace persistence adapter — same rationale/pattern as
 * {@code JpaOrganizationRepositoryTest}. {@code workspaces.organization_id} has a real FK to {@code
 * organizations} (same-module, the migration's own comment) — every workspace below is attached to
 * a real, persisted {@link Organization} row, never a bare random {@link UUID}, or the insert
 * itself would fail the FK constraint before this test could observe anything.
 */
@SpringBootTest(classes = JpaWorkspaceRepositoryTest.TestConfig.class)
@Testcontainers
class JpaWorkspaceRepositoryTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Autowired private WorkspaceRepository repository;

  @Autowired private SpringDataWorkspaceJpaRepository springDataRepository;

  @Autowired private OrganizationRepository organizations;

  private UUID newPersistedOrganizationId() {
    Organization organization = Organization.register("Test Org", UUID.randomUUID());
    organizations.save(organization);
    return organization.id();
  }

  @Test
  void savesAWorkspaceAndPersistsItsRealFields() {
    UUID organizationId = newPersistedOrganizationId();
    Workspace workspace = Workspace.register(organizationId, "Engineering");

    repository.save(workspace);

    WorkspaceEntity persisted = springDataRepository.findById(workspace.id()).orElseThrow();
    assertThat(persisted.getId()).isEqualTo(workspace.id());
    assertThat(persisted.getOrganizationId()).isEqualTo(organizationId);
    assertThat(persisted.getName()).isEqualTo("Engineering");
    assertThat(persisted.getCreatedAt())
        .isCloseTo(workspace.createdAt(), within(1, ChronoUnit.MILLIS));
  }

  @Test
  void findAllByOrganizationIdReturnsOnlyThatOrganizationsWorkspaces() {
    UUID organizationA = newPersistedOrganizationId();
    UUID organizationB = newPersistedOrganizationId();
    Workspace ownedByA1 = Workspace.register(organizationA, "A's First Workspace");
    Workspace ownedByA2 = Workspace.register(organizationA, "A's Second Workspace");
    Workspace ownedByB = Workspace.register(organizationB, "B's Workspace");
    repository.save(ownedByA1);
    repository.save(ownedByA2);
    repository.save(ownedByB);

    List<Workspace> found = repository.findAllByOrganizationId(organizationA);

    assertThat(found)
        .extracting(Workspace::id)
        .containsExactlyInAnyOrder(ownedByA1.id(), ownedByA2.id());
  }

  @Test
  void findByIdReturnsEmptyForAnUnknownId() {
    assertThat(repository.findById(UUID.randomUUID())).isEmpty();
  }

  // TD-PERF-020: real-Postgres proof of the paginated sibling, newest-first — same
  // "reconstitute with explicit createdAt instants, not a real wall-clock gap" discipline
  // JpaOrganizationRepositoryTest's own identical test already establishes.
  @Test
  void findPageByOrganizationIdReturnsOnePageAtATimeNewestFirst() {
    UUID organizationId = newPersistedOrganizationId();
    Instant now = Instant.now();
    Workspace first = reconstituteAt(organizationId, "First Created", now.minusSeconds(20));
    Workspace second = reconstituteAt(organizationId, "Second Created", now.minusSeconds(10));
    Workspace third = reconstituteAt(organizationId, "Third Created", now);
    repository.save(first);
    repository.save(second);
    repository.save(third);
    // A different Organization's own Workspace must never leak into this one's own page.
    repository.save(Workspace.register(newPersistedOrganizationId(), "Someone Else's Workspace"));

    Page<Workspace> firstPage =
        repository.findPageByOrganizationId(organizationId, new PageRequest(0, 2));

    assertThat(firstPage.content())
        .extracting(Workspace::id)
        .containsExactly(third.id(), second.id());
    assertThat(firstPage.totalElements()).isEqualTo(3);
    assertThat(firstPage.hasNext()).isTrue();

    Page<Workspace> secondPage =
        repository.findPageByOrganizationId(organizationId, new PageRequest(1, 2));

    assertThat(secondPage.content()).extracting(Workspace::id).containsExactly(first.id());
    assertThat(secondPage.hasNext()).isFalse();
  }

  private static Workspace reconstituteAt(
      final UUID organizationId, final String name, final Instant createdAt) {
    return Workspace.reconstitute(UUID.randomUUID(), organizationId, name, createdAt);
  }

  @Configuration
  @EnableAutoConfiguration
  @EnableJpaRepositories(
      basePackageClasses = {
        SpringDataWorkspaceJpaRepository.class,
        SpringDataOrganizationJpaRepository.class
      })
  @Import({JpaWorkspaceRepository.class, JpaOrganizationRepository.class})
  static class TestConfig {}
}
