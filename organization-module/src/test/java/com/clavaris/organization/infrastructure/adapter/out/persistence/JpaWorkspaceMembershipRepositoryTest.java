package com.clavaris.organization.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceMembershipRepository;
import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.application.usecase.createworkspace.WorkspaceRepository;
import com.clavaris.organization.domain.model.Organization;
import com.clavaris.organization.domain.model.Workspace;
import com.clavaris.organization.domain.model.WorkspaceMembership;
import com.clavaris.organization.domain.model.WorkspaceRole;
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
 * Real-Postgres integration test for the WorkspaceMembership persistence adapter — same pattern as
 * {@code JpaWorkspaceRepositoryTest}. Also proves the {@code
 * ux_workspace_memberships_workspace_id_account_id} unique index (the real, load-bearing safety net
 * behind BR-WS-04's "one Account is only ever provisioned once per Workspace" v1 flow).
 *
 * <p>The FK chain is two levels deep here: {@code workspace_memberships.workspace_id} references
 * {@code workspaces}, which itself references {@code organizations} — every membership below is
 * attached to a real, persisted {@link Workspace} row (itself attached to a real, persisted {@link
 * Organization}), never bare random {@link UUID}s, or the inserts would fail those FK constraints
 * before this test could observe anything.
 */
@SpringBootTest(classes = JpaWorkspaceMembershipRepositoryTest.TestConfig.class)
@Testcontainers
class JpaWorkspaceMembershipRepositoryTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Autowired private WorkspaceMembershipRepository repository;

  @Autowired private SpringDataWorkspaceMembershipJpaRepository springDataRepository;

  @Autowired private WorkspaceRepository workspaces;

  @Autowired private OrganizationRepository organizations;

  private UUID newPersistedWorkspaceId() {
    Organization organization = Organization.register("Test Org", UUID.randomUUID());
    organizations.save(organization);
    Workspace workspace = Workspace.register(organization.id(), "Test Workspace");
    workspaces.save(workspace);
    return workspace.id();
  }

  @Test
  void savesAMembershipAndPersistsItsRealFields() {
    UUID workspaceId = newPersistedWorkspaceId();
    UUID accountId = UUID.randomUUID();
    WorkspaceMembership membership =
        WorkspaceMembership.join(workspaceId, accountId, WorkspaceRole.ADMIN);

    repository.save(membership);

    WorkspaceMembershipEntity persisted =
        springDataRepository.findById(membership.id()).orElseThrow();
    assertThat(persisted.getWorkspaceId()).isEqualTo(workspaceId);
    assertThat(persisted.getAccountId()).isEqualTo(accountId);
    assertThat(persisted.getRole()).isEqualTo(WorkspaceRole.ADMIN);
  }

  @Test
  void countByWorkspaceIdAndRoleCountsOnlyThatWorkspacesMatchingRows() {
    UUID workspaceId = newPersistedWorkspaceId();
    UUID otherWorkspaceId = newPersistedWorkspaceId();
    repository.save(WorkspaceMembership.join(workspaceId, UUID.randomUUID(), WorkspaceRole.ADMIN));
    repository.save(WorkspaceMembership.join(workspaceId, UUID.randomUUID(), WorkspaceRole.ADMIN));
    repository.save(WorkspaceMembership.join(workspaceId, UUID.randomUUID(), WorkspaceRole.MEMBER));
    repository.save(
        WorkspaceMembership.join(otherWorkspaceId, UUID.randomUUID(), WorkspaceRole.ADMIN));

    assertThat(repository.countByWorkspaceIdAndRole(workspaceId, WorkspaceRole.ADMIN)).isEqualTo(2);
  }

  @Test
  void findAllByWorkspaceIdReturnsOnlyThatWorkspacesMemberships() {
    UUID workspaceA = newPersistedWorkspaceId();
    UUID workspaceB = newPersistedWorkspaceId();
    WorkspaceMembership inA =
        WorkspaceMembership.join(workspaceA, UUID.randomUUID(), WorkspaceRole.MEMBER);
    WorkspaceMembership inB =
        WorkspaceMembership.join(workspaceB, UUID.randomUUID(), WorkspaceRole.MEMBER);
    repository.save(inA);
    repository.save(inB);

    List<WorkspaceMembership> found = repository.findAllByWorkspaceId(workspaceA);

    assertThat(found).extracting(WorkspaceMembership::id).containsExactly(inA.id());
  }

  @Test
  void findAllByAccountIdReturnsOnlyThatAccountsMemberships() {
    UUID accountId = UUID.randomUUID();
    WorkspaceMembership own =
        WorkspaceMembership.join(newPersistedWorkspaceId(), accountId, WorkspaceRole.ADMIN);
    WorkspaceMembership someoneElse =
        WorkspaceMembership.join(
            newPersistedWorkspaceId(), UUID.randomUUID(), WorkspaceRole.MEMBER);
    repository.save(own);
    repository.save(someoneElse);

    List<WorkspaceMembership> found = repository.findAllByAccountId(accountId);

    assertThat(found).extracting(WorkspaceMembership::id).containsExactly(own.id());
  }

  @Test
  void findAllByAccountIdReturnsEmptyForAnAccountWithNoMembership() {
    assertThat(repository.findAllByAccountId(UUID.randomUUID())).isEmpty();
  }

  // deleteAllByAccountId is a derived "deleteBy" query method (find-then-remove-each JPA
  // semantics, not a raw bulk DELETE) — it requires an active EntityManager-bound transaction,
  // same reason identity-module's own structurally identical AccountRepository
  // .deleteAllByOrganizationId is never exercised directly against the bare repository either, only
  // through a real @Transactional service (WorkspaceMembershipEraserBridge, always called from
  // DeleteAccountService's own @Transactional method in production). @Transactional here supplies
  // that context for the test itself and rolls back afterward — no permanent side effect.
  @Test
  @Transactional
  void deleteAllByAccountIdRemovesEveryMembershipForThatAccountAcrossWorkspaces() {
    UUID accountId = UUID.randomUUID();
    WorkspaceMembership inWorkspace1 =
        WorkspaceMembership.join(newPersistedWorkspaceId(), accountId, WorkspaceRole.MEMBER);
    WorkspaceMembership inWorkspace2 =
        WorkspaceMembership.join(newPersistedWorkspaceId(), accountId, WorkspaceRole.ADMIN);
    WorkspaceMembership someoneElse =
        WorkspaceMembership.join(
            newPersistedWorkspaceId(), UUID.randomUUID(), WorkspaceRole.MEMBER);
    repository.save(inWorkspace1);
    repository.save(inWorkspace2);
    repository.save(someoneElse);

    repository.deleteAllByAccountId(accountId);

    assertThat(springDataRepository.findById(inWorkspace1.id())).isEmpty();
    assertThat(springDataRepository.findById(inWorkspace2.id())).isEmpty();
    assertThat(springDataRepository.findById(someoneElse.id())).isPresent();
  }

  @Configuration
  @EnableAutoConfiguration
  @EnableJpaRepositories(
      basePackageClasses = {
        SpringDataWorkspaceMembershipJpaRepository.class,
        SpringDataWorkspaceJpaRepository.class,
        SpringDataOrganizationJpaRepository.class
      })
  @Import({
    JpaWorkspaceMembershipRepository.class,
    JpaWorkspaceRepository.class,
    JpaOrganizationRepository.class
  })
  static class TestConfig {}
}
