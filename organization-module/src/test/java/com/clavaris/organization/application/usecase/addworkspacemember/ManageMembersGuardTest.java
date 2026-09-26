package com.clavaris.organization.application.usecase.addworkspacemember;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.organization.application.usecase.createworkspace.WorkspaceRoleRepository;
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
        .isThrownBy(
            () ->
                ManageMembersGuard.assertActionKeepsAtLeastOneHolder(
                    memberships,
                    roles,
                    workspaceId,
                    organizationId,
                    role.id(),
                    null,
                    CannotRemoveLastHolderExceptionForTest::new));
  }

  @Test
  void throwsWhenReassigningTheLastHolderToARoleWithoutManageMembers() {
    final WorkspaceRole role = manageMembersRole();
    final WorkspaceRole plain = plainRole();
    when(roles.findAllByOrganizationId(organizationId)).thenReturn(List.of(role, plain));
    when(memberships.findAllByWorkspaceId(workspaceId))
        .thenReturn(List.of(membershipWithRole(role.id())));

    assertThatExceptionOfType(CannotRemoveLastHolderExceptionForTest.class)
        .isThrownBy(
            () ->
                ManageMembersGuard.assertActionKeepsAtLeastOneHolder(
                    memberships,
                    roles,
                    workspaceId,
                    organizationId,
                    role.id(),
                    plain.id(),
                    CannotRemoveLastHolderExceptionForTest::new));
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

  private WorkspaceMembership membershipWithRole(final UUID roleId) {
    return WorkspaceMembership.join(workspaceId, UUID.randomUUID(), roleId);
  }

  /** A caller-supplied exception type stand-in, proving the guard throws whatever it's given. */
  private static final class CannotRemoveLastHolderExceptionForTest extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }
}
