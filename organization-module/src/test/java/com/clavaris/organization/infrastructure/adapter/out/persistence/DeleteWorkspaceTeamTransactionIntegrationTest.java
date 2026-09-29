package com.clavaris.organization.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceMembershipRepository;
import com.clavaris.organization.application.usecase.changeworkspacememberrole.CannotDemoteLastAdminException;
import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.application.usecase.createworkspace.WorkspaceRepository;
import com.clavaris.organization.application.usecase.createworkspace.WorkspaceRoleRepository;
import com.clavaris.organization.application.usecase.createworkspaceteam.WorkspaceTeamRepository;
import com.clavaris.organization.application.usecase.deleteorganization.EventOutboxWriter;
import com.clavaris.organization.application.usecase.deleteworkspacerole.DeleteWorkspaceRoleService;
import com.clavaris.organization.application.usecase.deleteworkspaceteam.DeleteWorkspaceTeamCommand;
import com.clavaris.organization.application.usecase.deleteworkspaceteam.DeleteWorkspaceTeamService;
import com.clavaris.organization.application.usecase.deleteworkspaceteam.DeleteWorkspaceTeamUseCase;
import com.clavaris.organization.domain.model.Organization;
import com.clavaris.organization.domain.model.ReservedWorkspacePermissions;
import com.clavaris.organization.domain.model.Workspace;
import com.clavaris.organization.domain.model.WorkspaceMembership;
import com.clavaris.organization.domain.model.WorkspaceRole;
import com.clavaris.organization.domain.model.WorkspaceTeam;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real-Spring-proxy, real-Postgres reproduction of a transaction-propagation bug found during this
 * branch's own SonarQube-style self-review, 2026-09-29: {@link DeleteWorkspaceTeamService#handle}
 * and {@link DeleteWorkspaceRoleService#handle} are BOTH {@code @Transactional} (default {@code
 * REQUIRED} propagation) — calling the latter from the former (a real cross-bean call through
 * Spring's own AOP proxy, not a same-class self-invocation) makes it join the SAME physical
 * transaction. When {@code DeleteWorkspaceRoleService#handle} throws one of its own guard
 * exceptions (all unchecked), Spring marks that shared transaction rollback-only BEFORE the
 * exception reaches {@code DeleteWorkspaceTeamService#deleteRoleIfNowOrphaned}'s own catch block —
 * catching it there does not clear that marker. {@code DeleteWorkspaceTeamService#handle} then
 * returns normally, its own {@code @Transactional} advice tries to commit, and Spring throws {@code
 * UnexpectedRollbackException} instead — silently discarding the team deletion that had already
 * "succeeded," on literally every team-delete that orphans an undeletable role (the exact case this
 * whole cascade feature exists to handle gracefully). A pure-Mockito unit test ({@code
 * DeleteWorkspaceTeamServiceTest}) can never catch this — {@code DeleteWorkspaceRoleUseCase} is
 * mocked there, so no real transactional proxy or physical transaction is ever involved.
 *
 * <p>Lives in this package (not alongside {@code DeleteWorkspaceTeamServiceTest}) because it needs
 * the real, package-private {@code Jpa*Repository}/{@code SpringData*JpaRepository} adapters — same
 * "concrete adapters, not the outbound ports, only reachable from their own package" constraint
 * {@code JpaWorkspaceTeamRepositoryTest}'s own placement already establishes.
 */
@SpringBootTest(classes = DeleteWorkspaceTeamTransactionIntegrationTest.TestConfig.class)
@Testcontainers
class DeleteWorkspaceTeamTransactionIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  private static final AuditActor ACTOR = AuditActor.platformClient("test-client");

  @Autowired private DeleteWorkspaceTeamUseCase deleteWorkspaceTeam;
  @Autowired private OrganizationRepository organizations;
  @Autowired private WorkspaceRepository workspaces;
  @Autowired private WorkspaceRoleRepository roles;
  @Autowired private WorkspaceTeamRepository teams;
  @Autowired private WorkspaceMembershipRepository memberships;

  // Reproduces the bug deterministically via CannotDeleteReservedWorkspaceRoleException (checked
  // first in DeleteWorkspaceRoleService#handle, before any membership/ManageMembersGuard
  // complexity) — every one of that service's own guard exceptions is equally affected, this is
  // just the simplest one to set up.
  @Test
  void stillCommitsTheTeamDeletionWhenAnOrphanedReservedRoleCannotBeDeleted() {
    Organization organization = Organization.register("Acme", UUID.randomUUID());
    organizations.save(organization);
    Workspace workspace = Workspace.register(organization.id(), "Engineering");
    workspaces.save(workspace);
    WorkspaceRole reserved = WorkspaceRole.defineReserved(organization.id(), "Admin");
    roles.save(reserved);
    WorkspaceTeam team = WorkspaceTeam.define(workspace.id(), "QA");
    teams.save(team);
    teams.addRoleToTeam(team.id(), reserved.id());

    assertThatCode(
            () ->
                deleteWorkspaceTeam.handle(
                    new DeleteWorkspaceTeamCommand(workspace.id(), team.id(), ACTOR)))
        .doesNotThrowAnyException();

    assertThat(teams.findById(team.id())).isEmpty();
    assertThat(roles.findById(reserved.id())).isPresent();
  }

  // Live UX request, 2026-09-29: proves the REAL rollback guarantee — unlike the reserved-role
  // case above (a tolerated exception, team deletion still succeeds),
  // CannotDemoteLastAdminException
  // now aborts the whole operation (DeleteWorkspaceTeamService's own Javadoc). A pure-Mockito unit
  // test can observe that teams.deleteById() gets *called* earlier in the method, but only a real
  // transaction can prove nothing was actually persisted once that call is rolled back.
  @Test
  void rollsBackTheTeamDeletionWhenAnOrphanedRoleWouldStripTheLastManageMembersHolder() {
    Organization organization = Organization.register("Acme", UUID.randomUUID());
    organizations.save(organization);
    Workspace workspace = Workspace.register(organization.id(), "Engineering");
    workspaces.save(workspace);
    WorkspaceRole manageMembersRole =
        WorkspaceRole.define(
            organization.id(), "Owner", null, Set.of(ReservedWorkspacePermissions.MANAGE_MEMBERS));
    roles.save(manageMembersRole);
    UUID accountId = UUID.randomUUID();
    memberships.save(WorkspaceMembership.join(workspace.id(), accountId, manageMembersRole.id()));
    WorkspaceTeam team = WorkspaceTeam.define(workspace.id(), "QA");
    teams.save(team);
    teams.addRoleToTeam(team.id(), manageMembersRole.id());
    DeleteWorkspaceTeamCommand command =
        new DeleteWorkspaceTeamCommand(workspace.id(), team.id(), ACTOR);

    assertThatExceptionOfType(CannotDemoteLastAdminException.class)
        .isThrownBy(() -> deleteWorkspaceTeam.handle(command));

    assertThat(teams.findById(team.id())).isPresent();
    assertThat(roles.findById(manageMembersRole.id())).isPresent();
    assertThat(memberships.findByWorkspaceIdAndAccountId(workspace.id(), accountId))
        .hasValueSatisfying(
            membership -> assertThat(membership.roleId()).isEqualTo(manageMembersRole.id()));
  }

  @Configuration
  @EnableAutoConfiguration
  @EnableJpaRepositories(
      basePackageClasses = {
        SpringDataWorkspaceTeamJpaRepository.class,
        SpringDataWorkspaceTeamRoleJpaRepository.class,
        SpringDataWorkspaceJpaRepository.class,
        SpringDataWorkspaceRoleJpaRepository.class,
        SpringDataWorkspaceMembershipJpaRepository.class,
        SpringDataOrganizationJpaRepository.class
      })
  @Import({
    JpaWorkspaceTeamRepository.class,
    JpaWorkspaceRepository.class,
    JpaWorkspaceRoleRepository.class,
    JpaWorkspaceMembershipRepository.class,
    JpaOrganizationRepository.class,
    DeleteWorkspaceTeamService.class,
    DeleteWorkspaceRoleService.class
  })
  static class TestConfig {

    @Bean
    /* package */ AuditEventRecorder auditEventRecorder() {
      return (actor, action, targetType, targetId, detail) -> {
        // No-op — this test is about transaction propagation, not auditing.
      };
    }

    @Bean
    /* package */ EventOutboxWriter eventOutboxWriter() {
      return (aggregateType, eventType, aggregateId, organizationId, payload) -> {
        // No-op — same rationale as auditEventRecorder() above.
      };
    }
  }
}
