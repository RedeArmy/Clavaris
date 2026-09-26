package com.clavaris.organization.application.usecase.addworkspacemember;

import com.clavaris.organization.application.usecase.createworkspace.WorkspaceRoleRepository;
import com.clavaris.organization.domain.model.ReservedWorkspacePermissions;
import com.clavaris.organization.domain.model.WorkspaceMembership;
import com.clavaris.organization.domain.model.WorkspaceRole;
import com.clavaris.organization.domain.service.WorkspaceRoleHierarchy;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * ADR-0027 §2: replaces {@code LastAdminGuard} — BR-WS-01's invariant, generalized from "at least
 * one {@code ADMIN}" to "at least one membership whose {@link WorkspaceRole}'s effective
 * permissions (parent-chain-inherited, {@link WorkspaceRoleHierarchy}) include {@link
 * ReservedWorkspacePermissions#MANAGE_MEMBERS}." Shared by every use case that can reduce a
 * Workspace's own count of such memberships ({@code removeworkspacemember}, {@code
 * changeworkspacememberrole}) — same "one shared guard, not two duplicated copies" precedent {@code
 * LastAdminGuard}'s own Javadoc already established, including the lock-then-count TOCTOU fix
 * (SDE-III review, 2026-09-03) this class keeps unchanged.
 */
public final class ManageMembersGuard {

  private ManageMembersGuard() {
    // Static utility — no instances.
  }

  /**
   * Locks the Workspace ({@link WorkspaceMembershipRepository#lockForRoleChange}) before counting,
   * same ordering rationale {@code LastAdminGuard} already documented. Loading this Organization's
   * own role set ({@link WorkspaceRoleRepository#findAllByOrganizationId}) is unavoidable up front
   * — telling whether either role even holds {@code MANAGE_MEMBERS} needs it, via {@link
   * WorkspaceRoleHierarchy#effectivePermissions}. The short-circuit that {@link
   * WorkspaceMembershipRepository#findAllByWorkspaceId} (the more expensive, per-membership call)
   * is skipped entirely whenever the change couldn't possibly reduce the count: the membership
   * being acted on didn't hold {@code MANAGE_MEMBERS} to begin with, or still holds it after the
   * change ({@code targetRoleId} is the new role, or {@code null} for an unassign/removal). Only
   * when a real reduction is possible does it load every membership and count actual remaining
   * holders.
   */
  // PMD.LongVariable: membershipCurrentRoleId/targetRoleId/exceptionIfViolated each name exactly
  // what they are — same convention this codebase's other descriptively-named parameters follow.
  // PMD.OnlyOneReturn: the two short-circuit exits and the final fall-through are three genuinely
  // distinct outcomes — same "each needs its own exit" rationale RegisterAccountController's own
  // identical suppression documents. PMD.AvoidLiteralsInIfCondition: the 1 is BR-WS-01's own
  // invariant spelled out literally ("at least one holder"), same rationale {@code LastAdminGuard}
  // (this class's own predecessor) already documented for its identical check.
  @SuppressWarnings({"PMD.LongVariable", "PMD.OnlyOneReturn", "PMD.AvoidLiteralsInIfCondition"})
  public static void assertActionKeepsAtLeastOneHolder(
      final WorkspaceMembershipRepository memberships,
      final WorkspaceRoleRepository roles,
      final UUID workspaceId,
      final UUID organizationId,
      final UUID membershipCurrentRoleId,
      final UUID targetRoleId,
      final Supplier<? extends RuntimeException> exceptionIfViolated) {
    memberships.lockForRoleChange(workspaceId);

    final Map<UUID, WorkspaceRole> rolesById =
        roles.findAllByOrganizationId(organizationId).stream()
            .collect(Collectors.toMap(WorkspaceRole::id, Function.identity()));

    if (!holdsManageMembers(membershipCurrentRoleId, rolesById)) {
      return;
    }
    if (holdsManageMembers(targetRoleId, rolesById)) {
      return;
    }

    final long remainingHolders =
        memberships.findAllByWorkspaceId(workspaceId).stream()
            .map(WorkspaceMembership::roleId)
            .filter(roleId -> holdsManageMembers(roleId, rolesById))
            .count();
    if (remainingHolders <= 1) {
      throw exceptionIfViolated.get();
    }
  }

  private static boolean holdsManageMembers(
      final UUID roleId, final Map<UUID, WorkspaceRole> rolesById) {
    return roleId != null
        && WorkspaceRoleHierarchy.effectivePermissions(roleId, rolesById)
            .contains(ReservedWorkspacePermissions.MANAGE_MEMBERS);
  }
}
