package com.clavaris.organization.application.usecase.assignworkspaceroletoaccount;

import com.clavaris.organization.domain.model.WorkspaceMembership;

/**
 * Live UX request, 2026-09-27: an "assign a role" action reachable both from the Workspace-detail
 * Teams tab (pick a team, then an account without a role in it) and from the Organization Users
 * tab's own existing "Assign role" popup (pick a Workspace/Team, then a role) — upserts, rather
 * than requiring an existing {@link WorkspaceMembership} first: an Organization account with zero
 * Workspace memberships so far gets one created; an account that already has one gets its role
 * replaced (same single-role-per-membership model {@code ChangeWorkspaceMemberRoleUseCase} already
 * enforces — this is not a second, additive role, it's the same "one role at a time" reassignment,
 * just able to also originate the membership itself).
 */
@FunctionalInterface
public interface AssignWorkspaceRoleToAccountUseCase {

  WorkspaceMembership handle(AssignWorkspaceRoleToAccountCommand command);
}
