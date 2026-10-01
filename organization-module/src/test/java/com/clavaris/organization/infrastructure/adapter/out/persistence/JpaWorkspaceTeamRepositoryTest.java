package com.clavaris.organization.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.application.usecase.createworkspace.WorkspaceRepository;
import com.clavaris.organization.application.usecase.createworkspace.WorkspaceRoleRepository;
import com.clavaris.organization.application.usecase.createworkspaceteam.WorkspaceTeamRepository;
import com.clavaris.organization.domain.model.Organization;
import com.clavaris.organization.domain.model.Workspace;
import com.clavaris.organization.domain.model.WorkspaceRole;
import com.clavaris.organization.domain.model.WorkspaceTeam;
import java.util.List;
import java.util.Map;
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
 * Real-Postgres integration test for the WorkspaceTeam persistence adapter (ADR-0028) — same
 * pattern as {@code JpaWorkspaceRoleRepositoryTest}. Also proves the two-step "resolve this
 * Workspace's own team ids, then filter workspace_team_roles by them" query shape {@code
 * JpaWorkspaceTeamRepository} uses instead of a JPA join.
 */
@SpringBootTest(classes = JpaWorkspaceTeamRepositoryTest.TestConfig.class)
@Testcontainers
class JpaWorkspaceTeamRepositoryTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Autowired private WorkspaceTeamRepository repository;

  @Autowired private OrganizationRepository organizations;

  @Autowired private WorkspaceRepository workspaces;

  @Autowired private WorkspaceRoleRepository roles;

  private UUID newPersistedWorkspaceId() {
    Organization organization = Organization.register("Test Org", UUID.randomUUID());
    organizations.save(organization);
    Workspace workspace = Workspace.register(organization.id(), "Test Workspace");
    workspaces.save(workspace);
    return workspace.id();
  }

  private UUID newPersistedRoleIdFor(final UUID organizationId) {
    WorkspaceRole role =
        WorkspaceRole.define(organizationId, "Test Role " + UUID.randomUUID(), null, Set.of());
    roles.save(role);
    return role.id();
  }

  @Test
  void savesAndFindsATeamWithItsRealFields() {
    UUID workspaceId = newPersistedWorkspaceId();
    WorkspaceTeam team = WorkspaceTeam.define(workspaceId, "QA");

    repository.save(team);

    WorkspaceTeam found = repository.findById(team.id()).orElseThrow();
    assertThat(found.id()).isEqualTo(team.id());
    assertThat(found.workspaceId()).isEqualTo(workspaceId);
    assertThat(found.name()).isEqualTo("QA");
  }

  @Test
  void findByIdReturnsEmptyForAnUnknownTeamId() {
    assertThat(repository.findById(UUID.randomUUID())).isEmpty();
  }

  @Test
  void findAllByWorkspaceIdReturnsOnlyThatWorkspacesTeams() {
    UUID workspaceA = newPersistedWorkspaceId();
    UUID workspaceB = newPersistedWorkspaceId();
    WorkspaceTeam inA = WorkspaceTeam.define(workspaceA, "QA");
    WorkspaceTeam inB = WorkspaceTeam.define(workspaceB, "Support");
    repository.save(inA);
    repository.save(inB);

    List<WorkspaceTeam> found = repository.findAllByWorkspaceId(workspaceA);

    assertThat(found).extracting(WorkspaceTeam::id).containsExactly(inA.id());
  }

  @Test
  void deleteByIdRemovesTheTeam() {
    UUID workspaceId = newPersistedWorkspaceId();
    WorkspaceTeam team = WorkspaceTeam.define(workspaceId, "Temporary");
    repository.save(team);

    repository.deleteById(team.id());

    assertThat(repository.findById(team.id())).isEmpty();
  }

  @Test
  void addRoleToTeamAndFindRoleIdsByTeamIdRoundTrip() {
    UUID workspaceId = newPersistedWorkspaceId();
    Organization organization = Organization.register("Role Org", UUID.randomUUID());
    organizations.save(organization);
    UUID roleId = newPersistedRoleIdFor(organization.id());
    WorkspaceTeam team = WorkspaceTeam.define(workspaceId, "QA");
    repository.save(team);

    repository.addRoleToTeam(team.id(), roleId);

    assertThat(repository.findRoleIdsByTeamId(team.id())).containsExactly(roleId);
  }

  @Test
  void removeRoleFromTeamDropsTheAssociation() {
    UUID workspaceId = newPersistedWorkspaceId();
    Organization organization = Organization.register("Role Org", UUID.randomUUID());
    organizations.save(organization);
    UUID roleId = newPersistedRoleIdFor(organization.id());
    WorkspaceTeam team = WorkspaceTeam.define(workspaceId, "QA");
    repository.save(team);
    repository.addRoleToTeam(team.id(), roleId);

    repository.removeRoleFromTeam(team.id(), roleId);

    assertThat(repository.findRoleIdsByTeamId(team.id())).isEmpty();
  }

  @Test
  void findRoleIdsByTeamIdsReturnsEveryRequestedTeamsOwnRolesInOneCall() {
    UUID workspaceId = newPersistedWorkspaceId();
    Organization organization = Organization.register("Role Org", UUID.randomUUID());
    organizations.save(organization);
    UUID roleId = newPersistedRoleIdFor(organization.id());
    WorkspaceTeam teamWithRole = WorkspaceTeam.define(workspaceId, "QA");
    repository.save(teamWithRole);
    repository.addRoleToTeam(teamWithRole.id(), roleId);
    WorkspaceTeam teamWithoutRole = WorkspaceTeam.define(workspaceId, "Support");
    repository.save(teamWithoutRole);

    Map<UUID, List<UUID>> found =
        repository.findRoleIdsByTeamIds(List.of(teamWithRole.id(), teamWithoutRole.id()));

    assertThat(found)
        .containsEntry(teamWithRole.id(), List.of(roleId))
        .containsEntry(teamWithoutRole.id(), List.of());
  }

  @Test
  void findTeamIdForRoleInWorkspaceFindsItOnlyWithinThatWorkspacesOwnTeams() {
    UUID workspaceA = newPersistedWorkspaceId();
    UUID workspaceB = newPersistedWorkspaceId();
    Organization organization = Organization.register("Role Org", UUID.randomUUID());
    organizations.save(organization);
    UUID roleId = newPersistedRoleIdFor(organization.id());
    WorkspaceTeam teamInA = WorkspaceTeam.define(workspaceA, "QA");
    repository.save(teamInA);
    repository.addRoleToTeam(teamInA.id(), roleId);
    WorkspaceTeam teamInB = WorkspaceTeam.define(workspaceB, "Support");
    repository.save(teamInB);

    assertThat(repository.findTeamIdForRoleInWorkspace(workspaceA, roleId)).contains(teamInA.id());
    assertThat(repository.findTeamIdForRoleInWorkspace(workspaceB, roleId)).isEmpty();
  }

  @Test
  void findAllGroupedRoleIdsForWorkspaceUnionsEveryTeamsOwnRoles() {
    UUID workspaceId = newPersistedWorkspaceId();
    Organization organization = Organization.register("Role Org", UUID.randomUUID());
    organizations.save(organization);
    UUID supervisorRoleId = newPersistedRoleIdFor(organization.id());
    UUID testerRoleId = newPersistedRoleIdFor(organization.id());
    UUID ungroupedRoleId = newPersistedRoleIdFor(organization.id());
    WorkspaceTeam team = WorkspaceTeam.define(workspaceId, "QA");
    repository.save(team);
    repository.addRoleToTeam(team.id(), supervisorRoleId);
    repository.addRoleToTeam(team.id(), testerRoleId);

    assertThat(repository.findAllGroupedRoleIdsForWorkspace(workspaceId))
        .containsExactlyInAnyOrder(supervisorRoleId, testerRoleId)
        .doesNotContain(ungroupedRoleId);
  }

  // Delete Workspace Team cascade feature, 2026-09-28: org-wide, deliberately not scoped to one
  // Workspace — the role may be grouped into a team of a DIFFERENT Workspace of the same
  // Organization (ADR-0028 §2).
  @Test
  void isRoleGroupedInAnyOtherTeamFindsAGroupingInADifferentWorkspaceOfTheSameOrganization() {
    UUID workspaceA = newPersistedWorkspaceId();
    UUID workspaceB = newPersistedWorkspaceId();
    Organization organization = Organization.register("Role Org", UUID.randomUUID());
    organizations.save(organization);
    UUID roleId = newPersistedRoleIdFor(organization.id());
    WorkspaceTeam teamInA = WorkspaceTeam.define(workspaceA, "QA");
    repository.save(teamInA);
    WorkspaceTeam teamInB = WorkspaceTeam.define(workspaceB, "Support");
    repository.save(teamInB);
    repository.addRoleToTeam(teamInB.id(), roleId);

    assertThat(repository.isRoleGroupedInAnyOtherTeam(roleId, teamInA.id())).isTrue();
  }

  @Test
  void isRoleGroupedInAnyOtherTeamExcludesTheGivenTeamsOwnGrouping() {
    UUID workspaceId = newPersistedWorkspaceId();
    Organization organization = Organization.register("Role Org", UUID.randomUUID());
    organizations.save(organization);
    UUID roleId = newPersistedRoleIdFor(organization.id());
    WorkspaceTeam team = WorkspaceTeam.define(workspaceId, "QA");
    repository.save(team);
    repository.addRoleToTeam(team.id(), roleId);

    assertThat(repository.isRoleGroupedInAnyOtherTeam(roleId, team.id())).isFalse();
  }

  @Test
  void isRoleGroupedInAnyOtherTeamIsFalseWhenTheRoleIsUngroupedEverywhere() {
    Organization organization = Organization.register("Role Org", UUID.randomUUID());
    organizations.save(organization);
    UUID roleId = newPersistedRoleIdFor(organization.id());

    assertThat(repository.isRoleGroupedInAnyOtherTeam(roleId, UUID.randomUUID())).isFalse();
  }

  // TD-ARCH-026: proves lockForTeamRoleChange is a real, callable Postgres advisory-lock query
  // (not a typo'd native SQL string that would only surface at first real use), same rationale
  // JpaSigningKeyRepositoryTest's own identical lockForRotation smoke test documents. Postgres
  // advisory locks are re-entrant within the same session/transaction, so a second call here must
  // not deadlock against the first.
  @Test
  void
      lockForTeamRoleChangeCompletesWithoutErrorAndDoesNotBlockASubsequentCallInTheSameTransaction() {
    UUID workspaceId = UUID.randomUUID();
    UUID roleId = UUID.randomUUID();

    org.assertj.core.api.Assertions.assertThatCode(
            () -> {
              repository.lockForTeamRoleChange(workspaceId, roleId);
              repository.lockForTeamRoleChange(workspaceId, roleId);
            })
        .doesNotThrowAnyException();
  }

  @Configuration
  @EnableAutoConfiguration
  @EnableJpaRepositories(
      basePackageClasses = {
        SpringDataWorkspaceTeamJpaRepository.class,
        SpringDataWorkspaceTeamRoleJpaRepository.class,
        SpringDataWorkspaceJpaRepository.class,
        SpringDataWorkspaceRoleJpaRepository.class,
        SpringDataOrganizationJpaRepository.class
      })
  @Import({
    JpaWorkspaceTeamRepository.class,
    JpaWorkspaceRepository.class,
    JpaWorkspaceRoleRepository.class,
    JpaOrganizationRepository.class
  })
  static class TestConfig {}
}
