package com.clavaris.organization.application.usecase.assignworkspaceroletoaccount;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceMembershipRepository;
import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceNotFoundException;
import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceRoleNotFoundException;
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
// PMD.LongVariable: roleBelongsToThisOrganization/accountBelongsToThisOrganization spell out
// exactly what each guard checks — same convention AddWorkspaceMemberService's own identical
// roleBelongsToThisOrganization variable already establishes for this exact check.
@SuppressWarnings("PMD.LongVariable")
public class AssignWorkspaceRoleToAccountService implements AssignWorkspaceRoleToAccountUseCase {

  // PMD.AvoidDuplicateLiterals: unlike AddWorkspaceMemberService/ChangeWorkspaceMemberRoleService
  // (2 occurrences each, below PMD's threshold), this class's own two branches (update vs.
  // originate) each carry their own audit+outbox pair, doubling the count — a real, not a false
  // positive, so extracted here rather than suppressed.
  private static final String AGGREGATE_TYPE = "WorkspaceMembership";

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

    // Deliberately NOT ManageMembersGuard here, unlike ChangeWorkspaceMemberRoleService (the REST
    // admin-API sibling a consuming application's own OrganizationClient can call). This use case
    // is reachable only from the platform operator's own dashboard session
    // (PlatformWorkspaceController
    // / PlatformAccountWorkspaceRoleController, both @Controller under /platform/dashboard/**,
    // never a consumer-facing endpoint) — BR-WS-01's own invariant ("a Workspace always has at
    // least one clavaris:workspace:manage_members holder") exists so the *consuming application's*
    // own logic (WorkspaceRoleClaimsCustomizer's own workspace_permissions claim is what it reads)
    // never finds itself with zero admins, not because Clavaris's own authorization logic depends
    // on it — Clavaris never gates any of its own endpoints on this permission string. The platform
    // operator, acting through Clavaris's own admin surface, is a deliberately higher trust tier
    // than a consuming application's own backend and may leave a Workspace in this state — the
    // operator made the call, not a generic tenant-reachable write path. Confirmed with the user,
    // 2026-10-01: ChangeWorkspaceMemberRoleService keeps enforcing this invariant unchanged.
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
          AGGREGATE_TYPE,
          updated.id().toString(),
          "previousRoleId=" + previousRoleId + " newRoleId=" + command.roleId());
      outbox.write(
          AGGREGATE_TYPE,
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
          AGGREGATE_TYPE,
          updated.id().toString(),
          "workspaceId=" + workspace.id() + " roleId=" + command.roleId());
      outbox.write(
          AGGREGATE_TYPE,
          "workspace_membership.added",
          updated.id(),
          workspace.organizationId(),
          WorkspaceMemberAddedEvent.from(updated));
    }

    return updated;
  }
}
