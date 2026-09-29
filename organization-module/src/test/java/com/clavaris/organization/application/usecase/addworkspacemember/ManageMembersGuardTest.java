package com.clavaris.organization.application.usecase.addworkspacemember;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.organization.application.usecase.createworkspace.WorkspaceRoleRepository;
import com.clavaris.organization.domain.model.ReservedWorkspacePermissions;
import com.clavaris.organization.domain.model.WorkspaceMembership;
import com.clavaris.organization.domain.model.WorkspaceRole;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

/**
 * ADR-0027 §2: {@link ManageMembersGuard} is {@code LastAdminGuard}'s generalized replacement — see
 * its own Javadoc for the invariant and the TOCTOU fix it keeps unchanged.
 */
class ManageMembersGuardTest {

  private final UUID organizationId = UUID.randomUUID();
  private final UUID workspaceId = UUID.randomUUID();
  private final WorkspaceMembershipRepository memberships =
      mock(WorkspaceMembershipRepository.class);
  private final WorkspaceRoleRepository roles = mock(WorkspaceRoleRepository.class);

  private WorkspaceRole manageMembersRole() {
    return WorkspaceRole.defineReserved(organizationId, "Admin");
  }

  private WorkspaceRole plainRole() {
    return WorkspaceRole.define(organizationId, "Member", null, Set.of());
  }

  // Extracted so assertThatExceptionOfType's own lambda below invokes exactly one method, not
  // this call plus the CannotRemoveLastHolderExceptionForTest::new reference alongside it —
  // same "one invocation per assertion lambda" discipline this codebase's other exception-
  // asserting tests already follow.
  private void assertKeepsAtLeastOneHolder(
      final WorkspaceRole currentRole, final UUID targetRoleId) {
    ManageMembersGuard.assertActionKeepsAtLeastOneHolder(
        memberships,
        roles,
        workspaceId,
        organizationId,
        currentRole.id(),
        targetRoleId,
        CannotRemoveLastHolderExceptionForTest::new);
  }

  @Test
  void locksBeforeLoadingRolesAndMemberships() {
    final WorkspaceRole role = manageMembersRole();
    when(roles.findAllByOrganizationId(organizationId)).thenReturn(List.of(role));
    when(memberships.findAllByWorkspaceId(workspaceId))
        .thenReturn(List.of(membershipWithRole(role.id()), membershipWithRole(role.id())));

    ManageMembersGuard.assertActionKeepsAtLeastOneHolder(
        memberships,
        roles,
        workspaceId,
        organizationId,
        role.id(),
        null,
        IllegalStateException::new);

    final InOrder inOrder = Mockito.inOrder(memberships, roles);
    inOrder.verify(memberships).lockForRoleChange(workspaceId);
    inOrder.verify(roles).findAllByOrganizationId(organizationId);
  }

  @Test
  void shortCircuitsWithoutLoadingEveryMembershipWhenTheCurrentRoleNeverHeldManageMembers() {
    final WorkspaceRole plain = plainRole();
    when(roles.findAllByOrganizationId(organizationId)).thenReturn(List.of(plain));

    ManageMembersGuard.assertActionKeepsAtLeastOneHolder(
        memberships,
        roles,
        workspaceId,
        organizationId,
        plain.id(),
        null,
        IllegalStateException::new);

    // Telling whether plain holds MANAGE_MEMBERS needs the role set (unavoidable) — the real
    // short-circuit is never paying for the more expensive per-membership scan below it.
    verify(memberships, never()).findAllByWorkspaceId(any());
  }

  @Test
  void doesNotThrowWhenTheTargetRoleStillHoldsManageMembers() {
    final WorkspaceRole role = manageMembersRole();
    when(roles.findAllByOrganizationId(organizationId)).thenReturn(List.of(role));

    ManageMembersGuard.assertActionKeepsAtLeastOneHolder(
        memberships,
        roles,
        workspaceId,
        organizationId,
        role.id(),
        role.id(),
        IllegalStateException::new);
  }

  @Test
  void doesNotThrowWhenMoreThanOneHolderRemainsAfterUnassigning() {
    final WorkspaceRole role = manageMembersRole();
    when(roles.findAllByOrganizationId(organizationId)).thenReturn(List.of(role));
    when(memberships.findAllByWorkspaceId(workspaceId))
        .thenReturn(List.of(membershipWithRole(role.id()), membershipWithRole(role.id())));

    ManageMembersGuard.assertActionKeepsAtLeastOneHolder(
        memberships,
        roles,
        workspaceId,
        organizationId,
        role.id(),
        null,
        IllegalStateException::new);
  }

  @Test
  void throwsTheCallerSuppliedExceptionWhenOnlyOneHolderRemains() {
    final WorkspaceRole role = manageMembersRole();
    when(roles.findAllByOrganizationId(organizationId)).thenReturn(List.of(role));
    when(memberships.findAllByWorkspaceId(workspaceId))
        .thenReturn(List.of(membershipWithRole(role.id())));

    assertThatExceptionOfType(CannotRemoveLastHolderExceptionForTest.class)
        .isThrownBy(() -> assertKeepsAtLeastOneHolder(role, null));
  }

  @Test
  void throwsWhenReassigningTheLastHolderToARoleWithoutManageMembers() {
    final WorkspaceRole role = manageMembersRole();
    final WorkspaceRole plain = plainRole();
    final UUID plainRoleId = plain.id();
    when(roles.findAllByOrganizationId(organizationId)).thenReturn(List.of(role, plain));
    when(memberships.findAllByWorkspaceId(workspaceId))
        .thenReturn(List.of(membershipWithRole(role.id())));

    assertThatExceptionOfType(CannotRemoveLastHolderExceptionForTest.class)
        .isThrownBy(() -> assertKeepsAtLeastOneHolder(role, plainRoleId));
  }

  @Test
  void inheritedPermissionsCountTowardTheHolderCheck() {
    final WorkspaceRole parent = manageMembersRole();
    final WorkspaceRole child =
        WorkspaceRole.define(organizationId, "Supervisor", parent.id(), Set.of());
    when(roles.findAllByOrganizationId(organizationId)).thenReturn(List.of(parent, child));
    when(memberships.findAllByWorkspaceId(workspaceId))
        .thenReturn(List.of(membershipWithRole(child.id()), membershipWithRole(child.id())));

    ManageMembersGuard.assertActionKeepsAtLeastOneHolder(
        memberships,
        roles,
        workspaceId,
        organizationId,
        child.id(),
        null,
        IllegalStateException::new);
  }

  // TD-ARCH-023: assertUnassigningRolesKeepsAtLeastOneHolder's own dedicated coverage.

  // The exact bug a real-Postgres integration test caught: a role can carry manage_members
  // (e.g. a freshly-seeded reserved role) while having ZERO current holders in this Workspace —
  // unassigning nobody can never reduce the holder count, so this must never throw.
  @Test
  void aggregateCheckDoesNotThrowWhenTheRoleBeingUnassignedHasNoCurrentHolders() {
    final WorkspaceRole role = manageMembersRole();
    when(roles.findAllByOrganizationId(organizationId)).thenReturn(List.of(role));
    when(memberships.findAllByWorkspaceId(workspaceId)).thenReturn(List.of());

    ManageMembersGuard.assertUnassigningRolesKeepsAtLeastOneHolder(
        memberships,
        roles,
        workspaceId,
        organizationId,
        Set.of(role.id()),
        IllegalStateException::new);
  }

  @Test
  void aggregateCheckThrowsWhenUnassigningTheOnlyRoleWithARealHolderStripsTheLastOne() {
    final WorkspaceRole role = manageMembersRole();
    when(roles.findAllByOrganizationId(organizationId)).thenReturn(List.of(role));
    when(memberships.findAllByWorkspaceId(workspaceId))
        .thenReturn(List.of(membershipWithRole(role.id())));

    assertThatExceptionOfType(CannotRemoveLastHolderExceptionForTest.class)
        .isThrownBy(
            () ->
                ManageMembersGuard.assertUnassigningRolesKeepsAtLeastOneHolder(
                    memberships,
                    roles,
                    workspaceId,
                    organizationId,
                    Set.of(role.id()),
                    CannotRemoveLastHolderExceptionForTest::new));
  }

  // The actual multi-role scenario TD-ARCH-023 is about: neither role alone holds the LAST
  // holder, but together they do — a per-role check could never see this, the aggregate one must.
  @Test
  void aggregateCheckThrowsWhenTwoRolesTogetherStripTheLastHolderEvenThoughNeitherAloneWould() {
    final WorkspaceRole roleA =
        WorkspaceRole.define(organizationId, "Owner A", null, ReservedWorkspacePermissions.ALL);
    final WorkspaceRole roleB =
        WorkspaceRole.define(organizationId, "Owner B", null, ReservedWorkspacePermissions.ALL);
    when(roles.findAllByOrganizationId(organizationId)).thenReturn(List.of(roleA, roleB));
    when(memberships.findAllByWorkspaceId(workspaceId))
        .thenReturn(List.of(membershipWithRole(roleA.id()), membershipWithRole(roleB.id())));

    assertThatExceptionOfType(CannotRemoveLastHolderExceptionForTest.class)
        .isThrownBy(
            () ->
                ManageMembersGuard.assertUnassigningRolesKeepsAtLeastOneHolder(
                    memberships,
                    roles,
                    workspaceId,
                    organizationId,
                    Set.of(roleA.id(), roleB.id()),
                    CannotRemoveLastHolderExceptionForTest::new));
  }

  @Test
  void aggregateCheckDoesNotThrowWhenAThirdRolesHolderSurvives() {
    final WorkspaceRole roleA =
        WorkspaceRole.define(organizationId, "Owner A", null, ReservedWorkspacePermissions.ALL);
    final WorkspaceRole roleB =
        WorkspaceRole.define(organizationId, "Owner B", null, ReservedWorkspacePermissions.ALL);
    final WorkspaceRole survivor = manageMembersRole();
    when(roles.findAllByOrganizationId(organizationId)).thenReturn(List.of(roleA, roleB, survivor));
    when(memberships.findAllByWorkspaceId(workspaceId))
        .thenReturn(
            List.of(
                membershipWithRole(roleA.id()),
                membershipWithRole(roleB.id()),
                membershipWithRole(survivor.id())));

    ManageMembersGuard.assertUnassigningRolesKeepsAtLeastOneHolder(
        memberships,
        roles,
        workspaceId,
        organizationId,
        Set.of(roleA.id(), roleB.id()),
        IllegalStateException::new);
  }

  private WorkspaceMembership membershipWithRole(final UUID roleId) {
    return WorkspaceMembership.join(workspaceId, UUID.randomUUID(), roleId);
  }

  /** A caller-supplied exception type stand-in, proving the guard throws whatever it's given. */
  private static final class CannotRemoveLastHolderExceptionForTest extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }
}
