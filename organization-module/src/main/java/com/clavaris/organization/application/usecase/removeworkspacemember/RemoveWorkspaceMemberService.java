package com.clavaris.organization.application.usecase.removeworkspacemember;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceMembershipRepository;
import com.clavaris.organization.application.usecase.deleteorganization.EventOutboxWriter;
import com.clavaris.organization.domain.event.WorkspaceMemberRemovedEvent;
import com.clavaris.organization.domain.model.WorkspaceMembership;
import com.clavaris.organization.domain.model.WorkspaceRole;
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
  private final AuditEventRecorder auditEvents;
  private final EventOutboxWriter outbox;

  public RemoveWorkspaceMemberService(
      final WorkspaceMembershipRepository memberships,
      final AuditEventRecorder auditEvents,
      final EventOutboxWriter outbox) {
    this.memberships = memberships;
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
        WorkspaceMemberRemovedEvent.of(
            membership.id(), membership.workspaceId(), membership.accountId()));
  }
}
