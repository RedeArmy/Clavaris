package com.clavaris.organization.application.usecase.deleteworkspace;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceMembershipRepository;
import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceNotFoundException;
import com.clavaris.organization.application.usecase.createworkspace.WorkspaceRepository;
import com.clavaris.organization.application.usecase.deleteorganization.EventOutboxWriter;
import com.clavaris.organization.application.usecase.removeworkspacemember.WorkspaceMemberAccountRevoker;
import com.clavaris.organization.application.usecase.removeworkspacemember.WorkspaceMemberRefreshTokenRevoker;
import com.clavaris.organization.domain.event.WorkspaceDeletedEvent;
import com.clavaris.organization.domain.model.Workspace;
import com.clavaris.organization.domain.model.WorkspaceMembership;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestration for {@link DeleteWorkspaceUseCase} — a real, permanent hard delete of one
 * Workspace, live UX request 2026-09-28 (dashboard's own Workspaces list, "Delete" next to "View").
 *
 * <p><b>Erasure is DB-cascade here, not application-layer</b> — deliberately, same posture {@code
 * DeleteOrganizationService}'s own Javadoc documents for its identical "no explicit
 * Workspace/WorkspaceMembership erasure step" note: {@code workspace_memberships} (migration {@code
 * V20260827130001}) and {@code workspace_teams} → {@code workspace_team_roles} (migrations {@code
 * V20260926100000}/{@code V20260926100001}) are all {@code ON DELETE CASCADE} from {@code
 * workspaces}, so the plain {@code workspaces.deleteById} call below already erases every
 * membership (the "automatic unlinking" of a user's role in this Workspace) and every team +
 * team/role grouping for this Workspace, no application-layer loop needed.
 *
 * <p><b>{@code workspace_roles} rows are deliberately NEVER touched here.</b> ADR-0027/0028: unlike
 * {@code WorkspaceTeam}, a {@code WorkspaceRole} is Organization-scoped, not Workspace-scoped — the
 * exact same role may be grouped into a team, and assigned to members, in a different Workspace of
 * this same Organization (see {@code WorkspaceRole}'s own Javadoc and {@code
 * WorkspaceMembershipRepository#existsByRoleId}'s own "global by roleId alone" note). Deleting this
 * Workspace only removes ITS OWN memberships/teams referencing a role — the role itself survives,
 * exactly as {@code DeleteWorkspaceTeamService} already establishes for "delete a team, the roles
 * in it survive, ungrouped" at the single-team scale. Cascading a role delete here would silently
 * corrupt any other Workspace of this Organization still relying on that same role.
 *
 * <p><b>TD-WS-004 (closed): every member's own live access is now revoked too</b>, not just their
 * {@code WorkspaceMembership} row — this Workspace's own membership list is loaded before the
 * cascade delete below erases it, and each member gets the exact same revocation cascade {@link
 * WorkspaceMemberRefreshTokenRevoker}/{@link WorkspaceMemberAccountRevoker} {@code
 * RemoveWorkspaceMemberService} already runs for a single-member removal. Deleting an entire
 * Workspace is a strictly larger membership-loss event than removing one member — it had no
 * business getting a *weaker* revocation guarantee than that single-member path.
 */
public class DeleteWorkspaceService implements DeleteWorkspaceUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(DeleteWorkspaceService.class);

  private final WorkspaceRepository workspaces;
  private final WorkspaceMembershipRepository memberships;
  private final AuditEventRecorder auditEvents;
  private final EventOutboxWriter outbox;

  // PMD.LongVariable: refreshTokenRevoker names exactly what it is — same convention
  // RemoveWorkspaceMemberService's own identical field documents.
  @SuppressWarnings("PMD.LongVariable")
  private final WorkspaceMemberRefreshTokenRevoker refreshTokenRevoker;

  private final WorkspaceMemberAccountRevoker accountRevoker;

  // java:S107: one parameter per collaborating port — same rationale as
  // RemoveWorkspaceMemberService's own identical suppression; TD-WS-004's own closure added the
  // three new ports (memberships, refreshTokenRevoker, accountRevoker) needed to mirror that
  // service's own revocation cascade.
  @SuppressWarnings("java:S107")
  public DeleteWorkspaceService(
      final WorkspaceRepository workspaces,
      final WorkspaceMembershipRepository memberships,
      final AuditEventRecorder auditEvents,
      final EventOutboxWriter outbox,
      @SuppressWarnings("PMD.LongVariable")
          final WorkspaceMemberRefreshTokenRevoker refreshTokenRevoker,
      final WorkspaceMemberAccountRevoker accountRevoker) {
    this.workspaces = workspaces;
    this.memberships = memberships;
    this.auditEvents = auditEvents;
    this.outbox = outbox;
    this.refreshTokenRevoker = refreshTokenRevoker;
    this.accountRevoker = accountRevoker;
  }

  // PMD.GuardLogStatement false positive — same rationale as every other logging call site in
  // this codebase (e.g. DeleteOrganizationService's own identical suppression).
  @SuppressWarnings("PMD.GuardLogStatement")
  @Override
  @Transactional
  public void handle(final DeleteWorkspaceCommand command) {
    // findById, not existsById: the full Workspace (specifically its name/organizationId) is
    // needed below to build WorkspaceDeletedEvent — same reasoning DeleteOrganizationService
    // already established for Organization.
    final Workspace workspace =
        workspaces
            .findById(command.workspaceId())
            .orElseThrow(() -> new WorkspaceNotFoundException(command.workspaceId()));

    // TD-WS-004: loaded before the cascade delete below erases every one of this Workspace's own
    // WorkspaceMembership rows — this is the only chance to know who needs their own access
    // revoked.
    // PMD.LongVariable: workspaceMemberships names exactly what it is — same convention this
    // codebase's other descriptively-named local variables already follow.
    @SuppressWarnings("PMD.LongVariable")
    final List<WorkspaceMembership> workspaceMemberships =
        memberships.findAllByWorkspaceId(workspace.id());

    auditEvents.write(
        command.actor(),
        "workspace.deleted",
        "Workspace",
        workspace.id().toString(),
        "organizationId=" + workspace.organizationId());

    outbox.write(
        "Workspace",
        "workspace.deleted",
        workspace.id(),
        workspace.organizationId(),
        WorkspaceDeletedEvent.from(workspace));

    LOG.info(
        "event=workspace_deleted workspaceId={} organizationId={}",
        workspace.id(),
        workspace.organizationId());

    workspaces.deleteById(workspace.id());

    // TD-WS-004: same "same transaction, no crash risk" reasoning RemoveWorkspaceMemberService's
    // own identical cascade documents — every revoked row and the cascade delete above live in
    // this one deployable's own single persistence unit.
    for (final WorkspaceMembership membership : workspaceMemberships) {
      refreshTokenRevoker.revokeAllRefreshTokensFor(membership.accountId());
      accountRevoker.revokeAllAccessFor(membership.accountId());
    }
  }
}
