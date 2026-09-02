package com.clavaris.organization.application.usecase.removeworkspacemember;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceMembershipRepository;
import com.clavaris.organization.application.usecase.createworkspace.WorkspaceRepository;
import com.clavaris.organization.application.usecase.deleteorganization.EventOutboxWriter;
import com.clavaris.organization.domain.event.WorkspaceMemberRemovedEvent;
import com.clavaris.organization.domain.model.WorkspaceMembership;
import com.clavaris.organization.domain.model.WorkspaceRole;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestration for {@link RemoveWorkspaceMemberUseCase}.
 *
 * <p>BR-WS-03, stated honestly: v1 has no workspace-scoped token/authorization concept — tokens are
 * Account/OAuthClient-scoped per Organization, not per-Workspace (`domain-model.md`). "Immediate
 * access revocation" in v1 means the admin-API listing and this method's own outbox event fire
 * synchronously with the delete below, not that a live token is invalidated (there is no such token
 * to invalidate yet). A consuming application that gates its own authorization by workspace
 * membership must re-check membership itself (or subscribe to this event once webhook-module
 * exists) — documented as a forward-looking limitation, not silently glossed over.
 */
public class RemoveWorkspaceMemberService implements RemoveWorkspaceMemberUseCase {

  private final WorkspaceMembershipRepository memberships;
  private final WorkspaceRepository workspaces;
  private final AuditEventRecorder auditEvents;
  private final EventOutboxWriter outbox;

  public RemoveWorkspaceMemberService(
      final WorkspaceMembershipRepository memberships,
      final WorkspaceRepository workspaces,
      final AuditEventRecorder auditEvents,
      final EventOutboxWriter outbox) {
    this.memberships = memberships;
    this.workspaces = workspaces;
    this.auditEvents = auditEvents;
    this.outbox = outbox;
  }

  @Override
  @Transactional
  public void handle(final RemoveWorkspaceMemberCommand command) {
    final WorkspaceMembership membership =
        memberships
            .findByWorkspaceIdAndAccountId(command.workspaceId(), command.accountId())
            .orElseThrow(
                () ->
                    new WorkspaceMembershipNotFoundException(
                        command.workspaceId(), command.accountId()));

    if (membership.role() == WorkspaceRole.ADMIN
        && memberships.countByWorkspaceIdAndRole(command.workspaceId(), WorkspaceRole.ADMIN) <= 1) {
      throw new CannotRemoveLastAdminException(command.workspaceId());
    }

    // webhook-module's own EventOutboxWriter needs organizationId — resolved before the delete
    // below so a concurrently-deleted Workspace (no v1 use case does this today, but nothing at
    // this layer forbids it) can't leave this lookup with nothing to find.
    final UUID organizationId =
        workspaces
            .findOrganizationIdById(membership.workspaceId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "WorkspaceMembership references workspaceId "
                            + membership.workspaceId()
                            + " that doesn't exist — data integrity violated before reaching this"
                            + " use case"));

    memberships.deleteById(membership.id());

    auditEvents.write(
        command.actor(),
        "workspace_membership.removed",
        "WorkspaceMembership",
        membership.id().toString(),
        "workspaceId=" + command.workspaceId());

    outbox.write(
        "WorkspaceMembership",
        "workspace_membership.removed",
        membership.id(),
        organizationId,
        WorkspaceMemberRemovedEvent.of(
            membership.id(), membership.workspaceId(), membership.accountId()));
  }
}
