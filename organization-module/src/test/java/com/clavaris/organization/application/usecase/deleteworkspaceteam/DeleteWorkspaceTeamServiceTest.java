package com.clavaris.organization.application.usecase.deleteworkspaceteam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceMembershipRepository;
import com.clavaris.organization.application.usecase.changeworkspacememberrole.CannotDemoteLastAdminException;
import com.clavaris.organization.application.usecase.createworkspace.WorkspaceRoleRepository;
import com.clavaris.organization.application.usecase.createworkspaceteam.WorkspaceTeamNotFoundException;
import com.clavaris.organization.application.usecase.createworkspaceteam.WorkspaceTeamRepository;
import com.clavaris.organization.application.usecase.deleteworkspacerole.CannotDeleteReservedWorkspaceRoleException;
import com.clavaris.organization.application.usecase.deleteworkspacerole.DeleteWorkspaceRoleCommand;
import com.clavaris.organization.application.usecase.deleteworkspacerole.DeleteWorkspaceRoleUseCase;
import com.clavaris.organization.application.usecase.deleteworkspacerole.WorkspaceRoleHasChildRolesException;
import com.clavaris.organization.application.usecase.deleteworkspacerole.WorkspaceRoleStillAssignedException;
import com.clavaris.organization.domain.model.ReservedWorkspacePermissions;
import com.clavaris.organization.domain.model.WorkspaceMembership;
import com.clavaris.organization.domain.model.WorkspaceRole;
import com.clavaris.organization.domain.model.WorkspaceTeam;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DeleteWorkspaceTeamServiceTest {

  private static final AuditActor ACTOR = AuditActor.platformClient("test-client");

  private WorkspaceTeamRepository teams;
  private DeleteWorkspaceRoleUseCase deleteRole;
  private AuditEventRecorder auditEvents;
  private WorkspaceMembershipRepository memberships;
  private WorkspaceRoleRepository roles;
  private DeleteWorkspaceTeamService service;

  private WorkspaceTeam team;
  private UUID organizationId;

  @BeforeEach
  void setUp() {
    teams = mock(WorkspaceTeamRepository.class);
    deleteRole = mock(DeleteWorkspaceRoleUseCase.class);
    auditEvents = mock(AuditEventRecorder.class);
    memberships = mock(WorkspaceMembershipRepository.class);
    roles = mock(WorkspaceRoleRepository.class);
    organizationId = UUID.randomUUID();
    team = WorkspaceTeam.define(UUID.randomUUID(), "QA");
    when(teams.findById(team.id())).thenReturn(Optional.of(team));
    // No manage_members holder among the roles this cascade would delete, by default — every
    // existing test below keeps this shape unless it explicitly overrides roles/memberships to
    // exercise the aggregate guard itself.
    when(roles.findAllByOrganizationId(organizationId)).thenReturn(List.of());
    service = new DeleteWorkspaceTeamService(teams, deleteRole, auditEvents, memberships, roles);
  }

  @Test
  void deletesTheTeam() {
    service.handle(
        new DeleteWorkspaceTeamCommand(organizationId, team.workspaceId(), team.id(), ACTOR));

    verify(teams).deleteById(team.id());
  }

  @Test
  void recordsAnAuditEvent() {
    service.handle(
        new DeleteWorkspaceTeamCommand(organizationId, team.workspaceId(), team.id(), ACTOR));

    verify(auditEvents)
        .write(
            eq(ACTOR),
            eq("workspace_team.deleted"),
            eq("WorkspaceTeam"),
            eq(team.id().toString()),
            any());
  }

  @Test
  void rejectsAnUnknownTeamId() {
    UUID unknownTeamId = UUID.randomUUID();
    when(teams.findById(unknownTeamId)).thenReturn(Optional.empty());
    DeleteWorkspaceTeamCommand command =
        new DeleteWorkspaceTeamCommand(organizationId, team.workspaceId(), unknownTeamId, ACTOR);

    assertThatExceptionOfType(WorkspaceTeamNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(teams, never()).deleteById(any());
  }

  @Test
  void rejectsATeamBelongingToADifferentWorkspace() {
    DeleteWorkspaceTeamCommand command =
        new DeleteWorkspaceTeamCommand(organizationId, UUID.randomUUID(), team.id(), ACTOR);

    assertThatExceptionOfType(WorkspaceTeamNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(teams, never()).deleteById(any());
  }

  // Live UX request, 2026-09-28: a role this team's own deletion orphans (not grouped or assigned
  // anywhere else) is now deleted too, not just left ungrouped.
  @Test
  void deletesARoleThatIsNowOrphanedByTheTeamsOwnDeletion() {
    UUID roleId = UUID.randomUUID();
    when(teams.findRoleIdsByTeamId(team.id())).thenReturn(List.of(roleId));
    when(teams.isRoleGroupedInAnyOtherTeam(roleId, team.id())).thenReturn(false);

    service.handle(
        new DeleteWorkspaceTeamCommand(organizationId, team.workspaceId(), team.id(), ACTOR));

    ArgumentCaptor<DeleteWorkspaceRoleCommand> captured =
        ArgumentCaptor.forClass(DeleteWorkspaceRoleCommand.class);
    verify(deleteRole).handle(captured.capture());
    assertThatIsForRoleAndWorkspace(captured.getValue(), roleId, team.workspaceId());
  }

  @Test
  void leavesARoleGroupedInAnotherTeamAlone() {
    UUID roleId = UUID.randomUUID();
    when(teams.findRoleIdsByTeamId(team.id())).thenReturn(List.of(roleId));
    when(teams.isRoleGroupedInAnyOtherTeam(roleId, team.id())).thenReturn(true);

    service.handle(
        new DeleteWorkspaceTeamCommand(organizationId, team.workspaceId(), team.id(), ACTOR));

    verify(deleteRole, never()).handle(any());
  }

  // None of DeleteWorkspaceRoleUseCase's own guard exceptions should abort the team deletion
  // itself — the team is already deleted by the time this attempt runs, and a role surviving
  // (ungrouped) is never treated as a failure of this operation.
  @Test
  void stillDeletesTheTeamWhenAnOrphanedRoleCannotBeSafelyDeleted() {
    UUID roleId = UUID.randomUUID();
    when(teams.findRoleIdsByTeamId(team.id())).thenReturn(List.of(roleId));
    when(teams.isRoleGroupedInAnyOtherTeam(roleId, team.id())).thenReturn(false);
    doThrow(new WorkspaceRoleStillAssignedException(roleId)).when(deleteRole).handle(any());

    service.handle(
        new DeleteWorkspaceTeamCommand(organizationId, team.workspaceId(), team.id(), ACTOR));

    verify(teams).deleteById(team.id());
  }

  @Test
  void alsoToleratesEveryOtherDeleteRoleGuardException() {
    UUID roleId = UUID.randomUUID();
    when(teams.findRoleIdsByTeamId(team.id())).thenReturn(List.of(roleId));
    when(teams.isRoleGroupedInAnyOtherTeam(roleId, team.id())).thenReturn(false);

    for (RuntimeException guardException :
        List.of(
            new WorkspaceRoleHasChildRolesException(roleId),
            new CannotDeleteReservedWorkspaceRoleException(roleId))) {
      doThrow(guardException).when(deleteRole).handle(any());

      service.handle(
          new DeleteWorkspaceTeamCommand(organizationId, team.workspaceId(), team.id(), ACTOR));
    }

    verify(teams, times(2)).deleteById(team.id());
  }

  // TD-ARCH-023 (fixed): CannotDemoteLastAdminException now aborts the WHOLE operation via a
  // single up-front aggregate check
  // (ManageMembersGuard#assertUnassigningRolesKeepsAtLeastOneHolder)
  // BEFORE the team row is ever touched — unlike the old REQUIRES_NEW-per-role design, this is now
  // genuinely provable in a pure-Mockito test: teams.deleteById() is asserted never called, not
  // merely "called but rolled back by a transaction this test can't see."
  @Test
  void
      abortsTheWholeOperationWhenDeletingTheOnlyOrphanedRoleWouldStripTheLastManageMembersHolder() {
    UUID workspaceId = team.workspaceId();
    UUID accountId = UUID.randomUUID();
    WorkspaceRole manageMembersRole =
        WorkspaceRole.define(
            organizationId, "Owner", null, Set.of(ReservedWorkspacePermissions.MANAGE_MEMBERS));
    WorkspaceMembership onlyHolder =
        WorkspaceMembership.join(workspaceId, accountId, manageMembersRole.id());
    when(teams.findRoleIdsByTeamId(team.id())).thenReturn(List.of(manageMembersRole.id()));
    when(teams.isRoleGroupedInAnyOtherTeam(manageMembersRole.id(), team.id())).thenReturn(false);
    when(roles.findAllByOrganizationId(organizationId)).thenReturn(List.of(manageMembersRole));
    when(memberships.findAllByWorkspaceId(workspaceId)).thenReturn(List.of(onlyHolder));
    DeleteWorkspaceTeamCommand command =
        new DeleteWorkspaceTeamCommand(organizationId, workspaceId, team.id(), ACTOR);

    assertThatExceptionOfType(CannotDemoteLastAdminException.class)
        .isThrownBy(() -> service.handle(command));

    verify(teams, never()).deleteById(any());
    verify(deleteRole, never()).handle(any());
    verifyNoInteractions(auditEvents);
  }

  // TD-ARCH-023's own actual bug scenario: neither role ALONE would strip the last holder, but
  // deleting BOTH in the same cascade would — the aggregate check must see the combined impact
  // across role boundaries, which per-role isolated checks (the old REQUIRES_NEW design) never
  // could. Both roles hold manage_members; each has its own distinct holder; removing both leaves
  // zero survivors.
  @Test
  void abortsWhenTwoOrphanedRolesTogetherWouldStripTheLastManageMembersHolder() {
    UUID workspaceId = team.workspaceId();
    WorkspaceRole roleA =
        WorkspaceRole.define(
            organizationId, "Owner A", null, Set.of(ReservedWorkspacePermissions.MANAGE_MEMBERS));
    WorkspaceRole roleB =
        WorkspaceRole.define(
            organizationId, "Owner B", null, Set.of(ReservedWorkspacePermissions.MANAGE_MEMBERS));
    WorkspaceMembership holderA =
        WorkspaceMembership.join(workspaceId, UUID.randomUUID(), roleA.id());
    WorkspaceMembership holderB =
        WorkspaceMembership.join(workspaceId, UUID.randomUUID(), roleB.id());
    when(teams.findRoleIdsByTeamId(team.id())).thenReturn(List.of(roleA.id(), roleB.id()));
    when(teams.isRoleGroupedInAnyOtherTeam(roleA.id(), team.id())).thenReturn(false);
    when(teams.isRoleGroupedInAnyOtherTeam(roleB.id(), team.id())).thenReturn(false);
    when(roles.findAllByOrganizationId(organizationId)).thenReturn(List.of(roleA, roleB));
    when(memberships.findAllByWorkspaceId(workspaceId)).thenReturn(List.of(holderA, holderB));
    DeleteWorkspaceTeamCommand command =
        new DeleteWorkspaceTeamCommand(organizationId, workspaceId, team.id(), ACTOR);

    assertThatExceptionOfType(CannotDemoteLastAdminException.class)
        .isThrownBy(() -> service.handle(command));

    verify(teams, never()).deleteById(any());
    verify(deleteRole, never()).handle(any());
  }

  // Same two-role setup as above, but with a THIRD role's holder surviving untouched — the
  // aggregate check must correctly allow this, not over-block just because manage_members roles
  // are involved at all.
  @Test
  void allowsTwoOrphanedRolesWhenAThirdSurvivingHolderRemains() {
    UUID workspaceId = team.workspaceId();
    WorkspaceRole roleA =
        WorkspaceRole.define(
            organizationId, "Owner A", null, Set.of(ReservedWorkspacePermissions.MANAGE_MEMBERS));
    WorkspaceRole roleB =
        WorkspaceRole.define(
            organizationId, "Owner B", null, Set.of(ReservedWorkspacePermissions.MANAGE_MEMBERS));
    WorkspaceRole survivingRole =
        WorkspaceRole.define(
            organizationId, "Owner C", null, Set.of(ReservedWorkspacePermissions.MANAGE_MEMBERS));
    WorkspaceMembership holderA =
        WorkspaceMembership.join(workspaceId, UUID.randomUUID(), roleA.id());
    WorkspaceMembership holderB =
        WorkspaceMembership.join(workspaceId, UUID.randomUUID(), roleB.id());
    WorkspaceMembership survivingHolder =
        WorkspaceMembership.join(workspaceId, UUID.randomUUID(), survivingRole.id());
    when(teams.findRoleIdsByTeamId(team.id())).thenReturn(List.of(roleA.id(), roleB.id()));
    when(teams.isRoleGroupedInAnyOtherTeam(roleA.id(), team.id())).thenReturn(false);
    when(teams.isRoleGroupedInAnyOtherTeam(roleB.id(), team.id())).thenReturn(false);
    when(roles.findAllByOrganizationId(organizationId))
        .thenReturn(List.of(roleA, roleB, survivingRole));
    when(memberships.findAllByWorkspaceId(workspaceId))
        .thenReturn(List.of(holderA, holderB, survivingHolder));
    DeleteWorkspaceTeamCommand command =
        new DeleteWorkspaceTeamCommand(organizationId, workspaceId, team.id(), ACTOR);

    service.handle(command);

    verify(teams).deleteById(team.id());
    verify(deleteRole, times(2)).handle(any());
  }

  // force=true skips the aggregate pre-check entirely — the whole operation proceeds, and every
  // nested DeleteWorkspaceRoleCommand this cascade builds is force=true too (the guard would be
  // redundant there in every case, since either the pre-check already validated safety, or the
  // caller explicitly asked to skip it).
  @Test
  void forceSkipsTheAggregateCheckAndThreadsForceIntoEveryNestedCommand() {
    UUID roleId = UUID.randomUUID();
    when(teams.findRoleIdsByTeamId(team.id())).thenReturn(List.of(roleId));
    when(teams.isRoleGroupedInAnyOtherTeam(roleId, team.id())).thenReturn(false);
    DeleteWorkspaceTeamCommand command =
        new DeleteWorkspaceTeamCommand(organizationId, team.workspaceId(), team.id(), true, ACTOR);

    service.handle(command);

    ArgumentCaptor<DeleteWorkspaceRoleCommand> captured =
        ArgumentCaptor.forClass(DeleteWorkspaceRoleCommand.class);
    verify(deleteRole).handle(captured.capture());
    assertThat(captured.getValue().force()).isTrue();
    verify(teams).deleteById(team.id());
    verifyNoInteractions(memberships);
    verifyNoInteractions(roles);
  }

  // Every nested DeleteWorkspaceRoleCommand this cascade builds is force=true unconditionally,
  // even without the caller's own command.force() being set — the aggregate pre-check above
  // already established aggregate safety, so the nested per-role guard would be redundant.
  @Test
  void alwaysForcesTheNestedDeleteRoleCommandOnceThePreCheckPasses() {
    UUID roleId = UUID.randomUUID();
    when(teams.findRoleIdsByTeamId(team.id())).thenReturn(List.of(roleId));
    when(teams.isRoleGroupedInAnyOtherTeam(roleId, team.id())).thenReturn(false);
    DeleteWorkspaceTeamCommand command =
        new DeleteWorkspaceTeamCommand(organizationId, team.workspaceId(), team.id(), ACTOR);

    service.handle(command);

    ArgumentCaptor<DeleteWorkspaceRoleCommand> captured =
        ArgumentCaptor.forClass(DeleteWorkspaceRoleCommand.class);
    verify(deleteRole).handle(captured.capture());
    assertThat(captured.getValue().force()).isTrue();
  }

  private static void assertThatIsForRoleAndWorkspace(
      final DeleteWorkspaceRoleCommand command, final UUID roleId, final UUID workspaceId) {
    assertThat(command.roleId()).isEqualTo(roleId);
    assertThat(command.workspaceId()).isEqualTo(workspaceId);
  }
}
