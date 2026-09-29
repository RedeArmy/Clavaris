package com.clavaris.organization.application.usecase.changeworkspacememberrole;

import com.clavaris.common.domain.model.AuditActor;
import java.util.UUID;

/**
 * @param newRoleId ADR-0027 — a {@code WorkspaceRole} id to reassign the membership to, or {@code
 *     null} to unassign it entirely (an explicitly allowed "no role" state, ADR-0027 §5) — unlike
 *     {@link
 *     com.clavaris.organization.application.usecase.addworkspacemember.AddWorkspaceMemberCommand#roleId},
 *     which must always reference a real role.
 * @param force Live UX request, 2026-09-29: {@code false} by default (the 4-arg constructor below)
 *     — when {@code true}, skips {@link
 *     com.clavaris.organization.application.usecase.addworkspacemember.ManageMembersGuard}'s own
 *     "at least one manage_members holder must remain" check entirely. {@code ManageMembersGuard}'s
 *     real purpose is protecting a CONSUMING application's own self-service governance (its own end
 *     users manage their Workspace's members/roles through {@code manage_members}/{@code
 *     manage_roles} permissions, reachable via the REST admin API) — the Clavaris platform
 *     dashboard itself is a strictly higher trust tier (a {@code PlatformAccount} already has
 *     unconditional access to its own Organization, never gated by any {@code WorkspaceRole}), so a
 *     deliberate, explicitly-confirmed dashboard override doesn't threaten the invariant this guard
 *     exists for. {@link ChangeWorkspaceMemberRoleController} (the REST surface) always uses the
 *     4-arg constructor — {@code force} must never be settable from external API input, only from
 *     {@code PlatformWorkspaceController}'s own dashboard handlers, after their own "type UNASSIGN
 *     to confirm" popup.
 */
public record ChangeWorkspaceMemberRoleCommand(
    UUID workspaceId, UUID accountId, UUID newRoleId, boolean force, AuditActor actor) {

  public ChangeWorkspaceMemberRoleCommand(
      final UUID workspaceId, final UUID accountId, final UUID newRoleId, final AuditActor actor) {
    this(workspaceId, accountId, newRoleId, false, actor);
  }
}
