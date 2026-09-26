package com.clavaris.organization.application.usecase.changeworkspacememberrole;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.organization.application.usecase.addworkspacemember.ManageMembersGuard;
import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceMembershipRepository;
import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceRoleNotFoundException;
import com.clavaris.organization.application.usecase.createworkspace.WorkspaceRepository;
import com.clavaris.organization.application.usecase.createworkspace.WorkspaceRoleRepository;
import com.clavaris.organization.application.usecase.deleteorganization.EventOutboxWriter;
import com.clavaris.organization.domain.event.WorkspaceMemberRoleChangedEvent;
import com.clavaris.organization.domain.model.WorkspaceMembership;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** Orchestration for {@link ChangeWorkspaceMemberRoleUseCase}. */
public class ChangeWorkspaceMemberRoleService implements ChangeWorkspaceMemberRoleUseCase {

  private final WorkspaceMembershipRepository memberships;
  private final WorkspaceRepository workspaces;
  private final WorkspaceRoleRepository roles;
  private final AuditEventRecorder auditEvents;
  private final EventOutboxWriter outbox;

  @SuppressWarnings("java:S107") // one parameter per collaborating port — same rationale as
  // AddWorkspaceMemberService's own identical suppression.
  public ChangeWorkspaceMemberRoleService(
      final WorkspaceMembershipRepository memberships,
      final WorkspaceRepository workspaces,
      final WorkspaceRoleRepository roles,
      final AuditEventRecorder auditEvents,
      final EventOutboxWriter outbox) {
    this.memberships = memberships;
    this.workspaces = workspaces;
    this.roles = roles;
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

    // webhook-module's own EventOutboxWriter needs organizationId — see
    // RemoveWorkspaceMemberService's own identical lookup for the full reasoning. Resolved before
    // the guard below too: ManageMembersGuard needs it to load this Organization's own roles.
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

    // ADR-0027: null is an explicitly allowed target (unassign) — only a real role id needs
    // validating against this Organization's own role set.
    if (command.newRoleId() != null
        && roles.findById(command.newRoleId()).stream()
            .noneMatch(role -> role.organizationId().equals(organizationId))) {
      throw new WorkspaceRoleNotFoundException(command.newRoleId());
    }

    // ADR-0027 §2: ManageMembersGuard replaces LastAdminGuard — see its own Javadoc for why it
    // short-circuits (no lock/load at all) whenever this change couldn't possibly reduce the
    // Workspace's manage_members-holder count.
    ManageMembersGuard.assertActionKeepsAtLeastOneHolder(
        memberships,
        roles,
        command.workspaceId(),
        organizationId,
        membership.roleId(),
        command.newRoleId(),
        () -> new CannotDemoteLastAdminException(command.workspaceId()));

    final UUID previousRoleId = membership.roleId();
    final WorkspaceMembership updated = membership.withRoleId(command.newRoleId());
    memberships.save(updated);

    auditEvents.write(
        command.actor(),
        "workspace_membership.role_changed",
        "WorkspaceMembership",
        updated.id().toString(),
        "previousRoleId=" + previousRoleId + " newRoleId=" + command.newRoleId());

    outbox.write(
        "WorkspaceMembership",
        "workspace_membership.role_changed",
        updated.id(),
        organizationId,
        WorkspaceMemberRoleChangedEvent.of(
            updated.id(),
            updated.workspaceId(),
            updated.accountId(),
            previousRoleId,
            updated.roleId()));

    return updated;
  }
}
