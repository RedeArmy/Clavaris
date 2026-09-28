package com.clavaris.organization.application.usecase.deleteworkspace;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceNotFoundException;
import com.clavaris.organization.application.usecase.createworkspace.WorkspaceRepository;
import com.clavaris.organization.application.usecase.deleteorganization.EventOutboxWriter;
import com.clavaris.organization.domain.event.WorkspaceDeletedEvent;
import com.clavaris.organization.domain.model.Workspace;
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
 */
public class DeleteWorkspaceService implements DeleteWorkspaceUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(DeleteWorkspaceService.class);

  private final WorkspaceRepository workspaces;
  private final AuditEventRecorder auditEvents;
  private final EventOutboxWriter outbox;

  public DeleteWorkspaceService(
      final WorkspaceRepository workspaces,
      final AuditEventRecorder auditEvents,
      final EventOutboxWriter outbox) {
    this.workspaces = workspaces;
    this.auditEvents = auditEvents;
    this.outbox = outbox;
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
  }
}
