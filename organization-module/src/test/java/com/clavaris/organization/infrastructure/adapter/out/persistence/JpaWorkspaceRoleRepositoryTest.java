package com.clavaris.organization.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.application.usecase.createworkspace.WorkspaceRoleRepository;
import com.clavaris.organization.domain.model.Organization;
import com.clavaris.organization.domain.model.WorkspaceRole;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
 * Real-Postgres integration test for the WorkspaceRole persistence adapter (ADR-0027) — same
 * pattern as {@code JpaWorkspaceMembershipRepositoryTest}. The one thing a mocked-repository unit
 * test can't prove: {@code permissions}'s own {@code ObjectMapper} JSON (de)serialization
 * round-trips correctly through the real {@code text} column, for both an empty set and a
 * multi-value one, and for the {@code reserved} role's own fixed permission pair.
 */
@SpringBootTest(classes = JpaWorkspaceRoleRepositoryTest.TestConfig.class)
@Testcontainers
class JpaWorkspaceRoleRepositoryTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Autowired private WorkspaceRoleRepository repository;

  @Autowired private OrganizationRepository organizations;

  private UUID newPersistedOrganizationId() {
    Organization organization = Organization.register("Test Org", UUID.randomUUID());
    organizations.save(organization);
    return organization.id();
  }

  @Test
  void savesAndFindsARoleWithItsRealFields() {
    UUID organizationId = newPersistedOrganizationId();
    WorkspaceRole role = WorkspaceRole.define(organizationId, "Supervisor", null, Set.of("a", "b"));

    repository.save(role);

    WorkspaceRole found = repository.findById(role.id()).orElseThrow();
    assertThat(found.id()).isEqualTo(role.id());
    assertThat(found.organizationId()).isEqualTo(organizationId);
    assertThat(found.name()).isEqualTo("Supervisor");
    assertThat(found.parentRoleId()).isNull();
    assertThat(found.permissions()).containsExactlyInAnyOrder("a", "b");
    assertThat(found.reserved()).isFalse();
  }

  @Test
  void roundTripsAnEmptyPermissionsSet() {
    UUID organizationId = newPersistedOrganizationId();
    WorkspaceRole role = WorkspaceRole.define(organizationId, "No Permissions", null, Set.of());

    repository.save(role);

    assertThat(repository.findById(role.id()).orElseThrow().permissions()).isEmpty();
  }

  @Test
  void roundTripsAParentRoleIdAndTheReservedFlag() {
    UUID organizationId = newPersistedOrganizationId();
    WorkspaceRole reserved = WorkspaceRole.defineReserved(organizationId, "Admin");
    repository.save(reserved);
    WorkspaceRole child = WorkspaceRole.define(organizationId, "Child", reserved.id(), Set.of("x"));

    repository.save(child);

    WorkspaceRole foundReserved = repository.findById(reserved.id()).orElseThrow();
    assertThat(foundReserved.reserved()).isTrue();
    assertThat(foundReserved.permissions())
        .containsExactlyInAnyOrderElementsOf(
            com.clavaris.organization.domain.model.ReservedWorkspacePermissions.ALL);
    WorkspaceRole foundChild = repository.findById(child.id()).orElseThrow();
    assertThat(foundChild.parentRoleId()).isEqualTo(reserved.id());
  }

  @Test
  void updatesAnExistingRoleInPlace() {
    UUID organizationId = newPersistedOrganizationId();
    WorkspaceRole role = WorkspaceRole.define(organizationId, "Original", null, Set.of("a"));
    repository.save(role);

    repository.save(role.withName("Renamed").withPermissions(Set.of("b")));

    WorkspaceRole updated = repository.findById(role.id()).orElseThrow();
    assertThat(updated.name()).isEqualTo("Renamed");
    assertThat(updated.permissions()).containsExactly("b");
  }

  @Test
  void findByIdReturnsEmptyForAnUnknownRoleId() {
    assertThat(repository.findById(UUID.randomUUID())).isEqualTo(Optional.empty());
  }

  @Test
  void findAllByOrganizationIdReturnsOnlyThatOrganizationsRoles() {
    UUID organizationA = newPersistedOrganizationId();
    UUID organizationB = newPersistedOrganizationId();
    WorkspaceRole inA = WorkspaceRole.define(organizationA, "In A", null, Set.of());
    WorkspaceRole inB = WorkspaceRole.define(organizationB, "In B", null, Set.of());
    repository.save(inA);
    repository.save(inB);

    List<WorkspaceRole> found = repository.findAllByOrganizationId(organizationA);

    assertThat(found).extracting(WorkspaceRole::id).containsExactly(inA.id());
  }

  @Test
  void deleteByIdRemovesTheRole() {
    UUID organizationId = newPersistedOrganizationId();
    WorkspaceRole role = WorkspaceRole.define(organizationId, "Temporary", null, Set.of());
    repository.save(role);

    repository.deleteById(role.id());

    assertThat(repository.findById(role.id())).isEmpty();
  }

  // CreateWorkspaceService's own reserved-role bootstrap race guard (ADR-0027) needs the
  // constraint violation to surface synchronously — see WorkspaceRoleRepository#saveAndFlush's
  // own Javadoc.
  @Test
  void saveAndFlushSurfacesAUniqueNameViolationSynchronously() {
    UUID organizationId = newPersistedOrganizationId();
    WorkspaceRole first = WorkspaceRole.define(organizationId, "Admin", null, Set.of());
    repository.saveAndFlush(first);
    WorkspaceRole conflicting = WorkspaceRole.define(organizationId, "Admin", null, Set.of());

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> repository.saveAndFlush(conflicting))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }

  @Configuration
  @EnableAutoConfiguration
  @EnableJpaRepositories(
      basePackageClasses = {
        SpringDataWorkspaceRoleJpaRepository.class,
        SpringDataOrganizationJpaRepository.class
      })
  @Import({JpaWorkspaceRoleRepository.class, JpaOrganizationRepository.class})
  static class TestConfig {}
}
