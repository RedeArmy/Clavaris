package com.clavaris.organization.application.usecase.assignworkspaceroletoaccount;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.organization.application.usecase.addworkspacemember.ManageMembersGuard;
import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceMembershipRepository;
import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceNotFoundException;
import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceRoleNotFoundException;
import com.clavaris.organization.application.usecase.changeworkspacememberrole.CannotDemoteLastAdminException;
import com.clavaris.organization.application.usecase.createworkspace.WorkspaceRepository;
import com.clavaris.organization.application.usecase.createworkspace.WorkspaceRoleRepository;
import com.clavaris.organization.application.usecase.deleteorganization.EventOutboxWriter;
import com.clavaris.organization.domain.event.WorkspaceMemberAddedEvent;
import com.clavaris.organization.domain.event.WorkspaceMemberRoleChangedEvent;
import com.clavaris.organization.domain.model.Workspace;
import com.clavaris.organization.domain.model.WorkspaceMembership;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** Orchestration for {@link AssignWorkspaceRoleToAccountUseCase} — see its own Javadoc. */
public class AssignWorkspaceRoleToAccountService implements AssignWorkspaceRoleToAccountUseCase {

  private final WorkspaceRepository workspaces;
  private final WorkspaceRoleRepository roles;
  private final WorkspaceMembershipRepository memberships;
  private final OrganizationAccountDirectory accountDirectory;
  private final AuditEventRecorder auditEvents;
  private final EventOutboxWriter outbox;

  // java:S107: one parameter per collaborating port — same rationale as
  // ChangeWorkspaceMemberRoleService's own identical constructor.
  @SuppressWarnings("java:S107")
  public AssignWorkspaceRoleToAccountService(
      final WorkspaceRepository workspaces,
      final WorkspaceRoleRepository roles,
      final WorkspaceMembershipRepository memberships,
      final OrganizationAccountDirectory accountDirectory,
      final AuditEventRecorder auditEvents,
      final EventOutboxWriter outbox) {
    this.workspaces = workspaces;
    this.roles = roles;
    this.memberships = memberships;
    this.accountDirectory = accountDirectory;
    this.auditEvents = auditEvents;
    this.outbox = outbox;
  }

  @Override
  @Transactional
  public WorkspaceMembership handle(final AssignWorkspaceRoleToAccountCommand command) {
    final Workspace workspace =
        workspaces
            .findById(command.workspaceId())
            .orElseThrow(() -> new WorkspaceNotFoundException(command.workspaceId()));

    final boolean roleBelongsToThisOrganization =
        roles.findById(command.roleId()).stream()
            .anyMatch(role -> role.organizationId().equals(workspace.organizationId()));
    if (!roleBelongsToThisOrganization) {
      throw new WorkspaceRoleNotFoundException(command.roleId());
    }

    // Anti-cross-tenant: see AccountNotInOrganizationException's own Javadoc for why this
    // re-resolution (rather than trusting the submitted accountId) is necessary here specifically
    // — every other cross-tenant-reachable id in this codebase can be re-verified against this
    // module's own data; accountId is the one exception, hence the cross-module directory call.
    final boolean accountBelongsToThisOrganization =
        accountDirectory.listAccountsForOrganization(workspace.organizationId()).stream()
            .anyMatch(account -> account.accountId().equals(command.accountId()));
    if (!accountBelongsToThisOrganization) {
      throw new AccountNotInOrganizationException(command.accountId(), workspace.organizationId());
    }

    final Optional<WorkspaceMembership> existing =
        memberships.findByWorkspaceIdAndAccountId(command.workspaceId(), command.accountId());
    final UUID previousRoleId = existing.map(WorkspaceMembership::roleId).orElse(null);

    // ADR-0027 §2: same ManageMembersGuard every other role-changing use case already applies —
    // reassigning someone away from a manage_members-holding role can't leave zero holders. A
    // brand-new membership (existing is empty) always has a null previousRoleId, so this
    // short-circuits immediately (they held nothing before, so there's nothing to protect).
    ManageMembersGuard.assertActionKeepsAtLeastOneHolder(
        memberships,
        roles,
        command.workspaceId(),
        workspace.organizationId(),
        previousRoleId,
        command.roleId(),
        () -> new CannotDemoteLastAdminException(command.workspaceId()));

    final WorkspaceMembership updated =
        existing
            .map(membership -> membership.withRoleId(command.roleId()))
            .orElseGet(
                () ->
                    WorkspaceMembership.join(
                        command.workspaceId(), command.accountId(), command.roleId()));
    memberships.save(updated);

    if (existing.isPresent()) {
      auditEvents.write(
          command.actor(),
          "workspace_membership.role_changed",
          "WorkspaceMembership",
          updated.id().toString(),
          "previousRoleId=" + previousRoleId + " newRoleId=" + command.roleId());
      outbox.write(
          "WorkspaceMembership",
          "workspace_membership.role_changed",
          updated.id(),
          workspace.organizationId(),
          WorkspaceMemberRoleChangedEvent.of(
              updated.id(),
              updated.workspaceId(),
              updated.accountId(),
              previousRoleId,
              updated.roleId()));
    } else {
      auditEvents.write(
          command.actor(),
          "workspace_membership.added",
          "WorkspaceMembership",
          updated.id().toString(),
          "workspaceId=" + workspace.id() + " roleId=" + command.roleId());
      outbox.write(
          "WorkspaceMembership",
          "workspace_membership.added",
          updated.id(),
          workspace.organizationId(),
          WorkspaceMemberAddedEvent.from(updated));
    }

    return updated;
  }
}
