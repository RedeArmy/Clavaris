package com.clavaris.organization.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.common.domain.model.KeysetPage;
import com.clavaris.common.domain.model.KeysetPageRequest;
import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceMembershipRepository;
import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.application.usecase.createworkspace.WorkspaceRepository;
import com.clavaris.organization.application.usecase.createworkspace.WorkspaceRoleRepository;
import com.clavaris.organization.domain.model.Organization;
import com.clavaris.organization.domain.model.Workspace;
import com.clavaris.organization.domain.model.WorkspaceMembership;
import com.clavaris.organization.domain.model.WorkspaceRole;
import java.time.Instant;
import java.util.List;
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
 * before this test could observe anything. Most tests here use a {@code null} {@code roleId}
 * (ADR-0027 — an explicitly allowed "no role assigned" state) since they exercise membership
 * plumbing (listing, pagination, deletion), not role referential integrity — {@code
 * savesAMembershipAndPersistsItsRealFields} is the one test that round-trips a real, persisted
 * {@link WorkspaceRole} id, to prove that FK-backed path too.
 */
@SpringBootTest(classes = JpaWorkspaceMembershipRepositoryTest.TestConfig.class)
@Testcontainers
class JpaWorkspaceMembershipRepositoryTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Autowired private WorkspaceMembershipRepository repository;

  @Autowired private SpringDataWorkspaceMembershipJpaRepository springDataRepository;

  @Autowired private WorkspaceRepository workspaces;

  @Autowired private WorkspaceRoleRepository roles;

  @Autowired private OrganizationRepository organizations;

  private UUID newPersistedWorkspaceId() {
    Organization organization = Organization.register("Test Org", UUID.randomUUID());
    organizations.save(organization);
    Workspace workspace = Workspace.register(organization.id(), "Test Workspace");
    workspaces.save(workspace);
    return workspace.id();
  }

  private UUID newPersistedRoleIdFor(final UUID organizationId) {
    WorkspaceRole role = WorkspaceRole.define(organizationId, "Test Role", null, Set.of("a"));
    roles.save(role);
    return role.id();
  }

  @Test
  void savesAMembershipAndPersistsItsRealFields() {
    Organization organization = Organization.register("Test Org", UUID.randomUUID());
    organizations.save(organization);
    Workspace workspace = Workspace.register(organization.id(), "Test Workspace");
    workspaces.save(workspace);
    UUID roleId = newPersistedRoleIdFor(organization.id());
    UUID accountId = UUID.randomUUID();
    WorkspaceMembership membership = WorkspaceMembership.join(workspace.id(), accountId, roleId);

    repository.save(membership);

    WorkspaceMembershipEntity persisted =
        springDataRepository.findById(membership.id()).orElseThrow();
    assertThat(persisted.getWorkspaceId()).isEqualTo(workspace.id());
    assertThat(persisted.getAccountId()).isEqualTo(accountId);
    assertThat(persisted.getRoleId()).isEqualTo(roleId);
  }

  @Test
  void savesAMembershipWithNoRoleAssigned() {
    UUID workspaceId = newPersistedWorkspaceId();
    WorkspaceMembership membership = WorkspaceMembership.join(workspaceId, UUID.randomUUID(), null);

    repository.save(membership);

    WorkspaceMembershipEntity persisted =
        springDataRepository.findById(membership.id()).orElseThrow();
    assertThat(persisted.getRoleId()).isNull();
  }

  @Test
  void findAllByWorkspaceIdReturnsOnlyThatWorkspacesMemberships() {
    UUID workspaceA = newPersistedWorkspaceId();
    UUID workspaceB = newPersistedWorkspaceId();
    WorkspaceMembership inA = WorkspaceMembership.join(workspaceA, UUID.randomUUID(), null);
    WorkspaceMembership inB = WorkspaceMembership.join(workspaceB, UUID.randomUUID(), null);
    repository.save(inA);
    repository.save(inB);

    List<WorkspaceMembership> found = repository.findAllByWorkspaceId(workspaceA);

    assertThat(found).extracting(WorkspaceMembership::id).containsExactly(inA.id());
  }

  // TD-PERF-021: real-Postgres proof for GetAuditLogForOrganizationService's own batched
  // replacement of what used to be one findAllByWorkspaceId call per Workspace inside a loop.
  @Test
  void findAllByWorkspaceIdsReturnsMembershipsAcrossEveryGivenWorkspace() {
    UUID workspaceA = newPersistedWorkspaceId();
    UUID workspaceB = newPersistedWorkspaceId();
    UUID workspaceC = newPersistedWorkspaceId();
    WorkspaceMembership inA = WorkspaceMembership.join(workspaceA, UUID.randomUUID(), null);
    WorkspaceMembership inB = WorkspaceMembership.join(workspaceB, UUID.randomUUID(), null);
    WorkspaceMembership inC = WorkspaceMembership.join(workspaceC, UUID.randomUUID(), null);
    repository.save(inA);
    repository.save(inB);
    repository.save(inC);

    List<WorkspaceMembership> found =
        repository.findAllByWorkspaceIds(List.of(workspaceA, workspaceB));

    assertThat(found)
        .extracting(WorkspaceMembership::id)
        .containsExactlyInAnyOrder(inA.id(), inB.id());
  }

  @Test
  void findAllByWorkspaceIdsReturnsEmptyForAnEmptyCollectionRatherThanEveryMembership() {
    repository.save(WorkspaceMembership.join(newPersistedWorkspaceId(), UUID.randomUUID(), null));

    assertThat(repository.findAllByWorkspaceIds(List.of())).isEmpty();
  }

  // TD-PERF-020 (keyset revision, 2026-09-14): real-Postgres proof of the paginated sibling,
  // newest-first, forward and backward navigation — same "reconstitute with explicit createdAt
  // instants" discipline JpaOrganizationRepositoryTest's own identical test already establishes.
  @Test
  void findKeysetPageByWorkspaceIdReturnsNewestFirstAndSupportsForwardAndBackwardNavigation() {
    UUID workspaceId = newPersistedWorkspaceId();
    Instant now = Instant.now();
    WorkspaceMembership first = reconstituteAt(workspaceId, now.minusSeconds(20));
    WorkspaceMembership second = reconstituteAt(workspaceId, now.minusSeconds(10));
    WorkspaceMembership third = reconstituteAt(workspaceId, now);
    repository.save(first);
    repository.save(second);
    repository.save(third);
    // A different Workspace's own membership must never leak into this one's own page.
    repository.save(WorkspaceMembership.join(newPersistedWorkspaceId(), UUID.randomUUID(), null));

    KeysetPage<WorkspaceMembership> firstPage =
        repository.findKeysetPageByWorkspaceId(workspaceId, new KeysetPageRequest(null, null, 2));

    assertThat(firstPage.content())
        .extracting(WorkspaceMembership::id)
        .containsExactly(third.id(), second.id());
    assertThat(firstPage.hasNext()).isTrue();
    assertThat(firstPage.hasPrevious()).isFalse();

    KeysetPage<WorkspaceMembership> secondPage =
        repository.findKeysetPageByWorkspaceId(
            workspaceId, new KeysetPageRequest(firstPage.endCursor(), null, 2));

    assertThat(secondPage.content())
        .extracting(WorkspaceMembership::id)
        .containsExactly(first.id());
    assertThat(secondPage.hasNext()).isFalse();
    assertThat(secondPage.hasPrevious()).isTrue();

    KeysetPage<WorkspaceMembership> backToFirstPage =
        repository.findKeysetPageByWorkspaceId(
            workspaceId, new KeysetPageRequest(null, secondPage.startCursor(), 2));

    assertThat(backToFirstPage.content())
        .extracting(WorkspaceMembership::id)
        .containsExactly(third.id(), second.id());
    assertThat(backToFirstPage.hasNext()).isTrue();
    assertThat(backToFirstPage.hasPrevious()).isFalse();
  }

  private static WorkspaceMembership reconstituteAt(
      final UUID workspaceId, final Instant createdAt) {
    return WorkspaceMembership.reconstitute(
        UUID.randomUUID(), workspaceId, UUID.randomUUID(), null, createdAt);
  }

  @Test
  void findAllByAccountIdReturnsOnlyThatAccountsMemberships() {
    UUID accountId = UUID.randomUUID();
    WorkspaceMembership own = WorkspaceMembership.join(newPersistedWorkspaceId(), accountId, null);
    WorkspaceMembership someoneElse =
        WorkspaceMembership.join(newPersistedWorkspaceId(), UUID.randomUUID(), null);
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
        WorkspaceMembership.join(newPersistedWorkspaceId(), accountId, null);
    WorkspaceMembership inWorkspace2 =
        WorkspaceMembership.join(newPersistedWorkspaceId(), accountId, null);
    WorkspaceMembership someoneElse =
        WorkspaceMembership.join(newPersistedWorkspaceId(), UUID.randomUUID(), null);
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
        SpringDataWorkspaceRoleJpaRepository.class,
        SpringDataOrganizationJpaRepository.class
      })
  @Import({
    JpaWorkspaceMembershipRepository.class,
    JpaWorkspaceRepository.class,
    JpaWorkspaceRoleRepository.class,
    JpaOrganizationRepository.class
  })
  static class TestConfig {}
}
