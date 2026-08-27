package com.clavaris.organization.application.usecase.changeworkspacememberrole;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceMembershipRepository;
import com.clavaris.organization.application.usecase.deleteorganization.EventOutboxWriter;
import com.clavaris.organization.domain.event.WorkspaceMemberRoleChangedEvent;
import com.clavaris.organization.domain.model.WorkspaceMembership;
import com.clavaris.organization.domain.model.WorkspaceRole;
import org.springframework.transaction.annotation.Transactional;

/** Orchestration for {@link ChangeWorkspaceMemberRoleUseCase}. */
public class ChangeWorkspaceMemberRoleService implements ChangeWorkspaceMemberRoleUseCase {

  private final WorkspaceMembershipRepository memberships;
  private final AuditEventRecorder auditEvents;
  private final EventOutboxWriter outbox;

  public ChangeWorkspaceMemberRoleService(
      final WorkspaceMembershipRepository memberships,
      final AuditEventRecorder auditEvents,
      final EventOutboxWriter outbox) {
    this.memberships = memberships;
    this.auditEvents = auditEvents;
    this.outbox = outbox;
  }

  @Override
  @Transactional
  public WorkspaceMembership handle(final ChangeWorkspaceMemberRoleCommand command) {
    final WorkspaceMembership membership =
        memberships
            .findByWorkspaceIdAndAccountId(command.workspaceId(), command.accountId())
            .orElseThrow(
                () ->
                    new WorkspaceMembershipNotFoundException(
                        command.workspaceId(), command.accountId()));

    // Only a demotion away from ADMIN can ever violate the invariant — promoting to ADMIN, or
    // "changing" a MEMBER to MEMBER, never reduces the ADMIN count.
    @SuppressWarnings("PMD.LongVariable")
    final boolean isDemotionFromAdmin =
        membership.role() == WorkspaceRole.ADMIN && command.newRole() != WorkspaceRole.ADMIN;
    if (isDemotionFromAdmin
        && memberships.countByWorkspaceIdAndRole(command.workspaceId(), WorkspaceRole.ADMIN) <= 1) {
      throw new CannotDemoteLastAdminException(command.workspaceId());
    }

    final WorkspaceRole previousRole = membership.role();
    final WorkspaceMembership updated = membership.withRole(command.newRole());
    memberships.save(updated);

    auditEvents.write(
        command.actor(),
        "workspace_membership.role_changed",
        "WorkspaceMembership",
        updated.id().toString(),
        "previousRole=" + previousRole + " newRole=" + command.newRole());

    outbox.write(
        "WorkspaceMembership",
        "workspace_membership.role_changed",
        updated.id(),
        WorkspaceMemberRoleChangedEvent.of(
            updated.id(),
            updated.workspaceId(),
            updated.accountId(),
            previousRole,
            updated.role()));

    return updated;
  }
}
