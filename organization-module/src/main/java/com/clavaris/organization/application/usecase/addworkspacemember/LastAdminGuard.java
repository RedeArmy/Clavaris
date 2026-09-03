package com.clavaris.organization.application.usecase.addworkspacemember;

import com.clavaris.organization.domain.model.WorkspaceRole;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * BR-WS-01 ("a workspace must always retain at least one ADMIN") — shared by every use case that
 * can reduce a Workspace's own ADMIN count ({@code removeworkspacemember}, {@code
 * changeworkspacememberrole}). Parked under {@code addworkspacemember}, same "first use case owns
 * the shared port" convention {@link WorkspaceMembershipRepository}'s own Javadoc already
 * establishes — this isn't itself a use case, it's the one guard both of the others need.
 *
 * <p>SDE-III review, 2026-09-03 — real bug found and closed: this guard used to be duplicated
 * verbatim in both call sites, each running its own unlocked {@code countByWorkspaceIdAndRole} read
 * — a genuine TOCTOU race (two concurrent requests against the same Workspace could each read "2
 * ADMINs remain," each pass, and both commit, leaving zero) on top of the duplication itself.
 * Extracting one shared, lock-then-count implementation closes both problems at once: there is now
 * exactly one place this invariant is checked, and it is checked correctly.
 */
public final class LastAdminGuard {

  private LastAdminGuard() {
    // Static utility — no instances.
  }

  /**
   * Locks the Workspace ({@link WorkspaceMembershipRepository#lockForRoleChange}) before counting —
   * see that method's own Javadoc for why the lock must come first — then throws {@code
   * exceptionIfViolated} if fewer than two ADMINs currently remain (i.e., removing/demoting the one
   * being acted on would leave zero). The exception itself is supplied by the caller, not fixed
   * here: {@code removeworkspacemember} and {@code changeworkspacememberrole} each throw their own
   * distinct type ({@code CannotRemoveLastAdminException}/{@code CannotDemoteLastAdminException})
   * for the same underlying violation.
   */
  // PMD.LongVariable: exceptionIfViolated names exactly what it is — same convention this
  // codebase's other descriptively-named parameters already follow. PMD.AvoidLiteralsInIfCondition:
  // the 1 is BR-WS-01's own invariant spelled out literally ("at least one ADMIN"), not a magic
  // number standing in for something else — a named constant here would just restate this method's
  // own name back at itself.
  @SuppressWarnings({"PMD.LongVariable", "PMD.AvoidLiteralsInIfCondition"})
  public static void assertAtLeastOneAdminWouldRemain(
      final WorkspaceMembershipRepository memberships,
      final UUID workspaceId,
      final Supplier<? extends RuntimeException> exceptionIfViolated) {
    memberships.lockForRoleChange(workspaceId);
    if (memberships.countByWorkspaceIdAndRole(workspaceId, WorkspaceRole.ADMIN) <= 1) {
      throw exceptionIfViolated.get();
    }
  }
}
