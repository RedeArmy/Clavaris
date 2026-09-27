package com.clavaris.organization.domain.service;

import com.clavaris.organization.domain.model.WorkspaceRole;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * ADR-0027 §3 — pure domain logic over an already-loaded {@code Map<UUID, WorkspaceRole>} for one
 * Organization (no repository/framework dependency, hexagonal rule applied to a service that
 * genuinely needs the whole role graph, not just one row): cycle detection before a {@code
 * parentRoleId} is persisted, and effective-permission resolution (a role's own {@code permissions}
 * unioned with every ancestor's, walked up the {@code parentRoleId} chain) — the mechanic that goes
 * further than Clerk/WorkOS/Auth0's flat role-permission bundles while staying just as opaque as
 * they are.
 */
public final class WorkspaceRoleHierarchy {

  private WorkspaceRoleHierarchy() {
    // Static utility only.
  }

  /**
   * True if setting {@code roleId}'s parent to {@code candidateParentId} would create a cycle —
   * i.e. walking up from {@code candidateParentId} ever reaches {@code roleId} again. Also true (a
   * defensive, not a strictly cycle-caused, rejection) if the walk revisits any role, which can
   * only happen if a cycle already exists somewhere else in the graph — this check must never
   * infinite-loop over an already-corrupt chain.
   */
  // PMD.OnlyOneReturn: the early "cycle found" exit and the final "reached the end, no cycle"
  // exit are two genuinely distinct outcomes of a graph walk — same "each needs its own exit"
  // rationale RegisterAccountController's own identical suppression documents.
  // PMD.NullAssignment: cursor = null is how this walk represents "no parent, chain ends here,"
  // not a code smell — the same sentinel effectivePermissions' own identical walk below uses.
  @SuppressWarnings({"PMD.OnlyOneReturn", "PMD.NullAssignment"})
  public static boolean wouldCreateCycle(
      final UUID roleId, final UUID candidateParentId, final Map<UUID, WorkspaceRole> rolesById) {
    final Set<UUID> visited = new HashSet<>();
    UUID cursor = candidateParentId;
    while (cursor != null) {
      if (cursor.equals(roleId) || !visited.add(cursor)) {
        return true;
      }
      final WorkspaceRole parent = rolesById.get(cursor);
      cursor = parent == null ? null : parent.parentRoleId();
    }
    return false;
  }

  /**
   * {@code roleId}'s own {@code permissions} unioned with every ancestor's, walked up the {@code
   * parentRoleId} chain. An unknown {@code roleId} (not present in {@code rolesById}) or a {@code
   * null} chain link stops the walk with whatever was accumulated so far, rather than throwing —
   * the same defensive posture {@link #wouldCreateCycle} takes against an already-corrupt graph.
   */
  public static Set<String> effectivePermissions(
      final UUID roleId, final Map<UUID, WorkspaceRole> rolesById) {
    final Set<String> effective = new HashSet<>();
    final Set<UUID> visited = new HashSet<>();
    UUID cursor = roleId;
    while (cursor != null && visited.add(cursor)) {
      final WorkspaceRole role = rolesById.get(cursor);
      if (role == null) {
        break;
      }
      effective.addAll(role.permissions());
      cursor = role.parentRoleId();
    }
    return Set.copyOf(effective);
  }
}
