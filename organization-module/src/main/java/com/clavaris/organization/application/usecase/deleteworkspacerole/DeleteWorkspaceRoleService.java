package com.clavaris.organization.application.usecase.deleteworkspacerole;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.organization.application.usecase.addworkspacemember.ManageMembersGuard;
import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceMembershipRepository;
import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceRoleNotFoundException;
import com.clavaris.organization.application.usecase.changeworkspacememberrole.CannotDemoteLastAdminException;
import com.clavaris.organization.application.usecase.createworkspace.WorkspaceRoleRepository;
import com.clavaris.organization.application.usecase.deleteorganization.EventOutboxWriter;
import com.clavaris.organization.domain.event.WorkspaceMemberRoleChangedEvent;
import com.clavaris.organization.domain.event.WorkspaceRoleDeletedEvent;
import com.clavaris.organization.domain.model.ReservedWorkspacePermissions;
import com.clavaris.organization.domain.model.WorkspaceMembership;
import com.clavaris.organization.domain.model.WorkspaceRole;
import com.clavaris.organization.domain.service.WorkspaceRoleHierarchy;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestration for {@link DeleteWorkspaceRoleUseCase}.
 *
 * <p>Live UX request, 2026-09-28: when {@link DeleteWorkspaceRoleCommand#workspaceId()} is present,
 * every member holding this role WITHIN that one Workspace is auto-unassigned (role set to {@code
 * null}, same "roleless member" state {@code ChangeWorkspaceMemberRoleCommand}'s own Javadoc
 * documents as explicitly allowed, ADR-0027 §5) before the "still assigned" check below — a role
 * still held in a DIFFERENT Workspace of the same Organization (roles are Organization- scoped and
 * shared, ADR-0027/0028) still blocks deletion exactly as before, since that check remains
 * org-wide. Each unassignment goes through {@link ManageMembersGuard}, same protection every other
 * role-changing use case already applies — bulk-clearing every holder of a role must not leave this
 * Workspace with zero {@code manage_members} holders, same invariant as a single change.
 *
 * <p><b>{@code Propagation.REQUIRES_NEW}, SonarQube-style branch self-review, 2026-09-29 — a real
 * bug found and fixed, not a style choice.</b> {@code
 * DeleteWorkspaceTeamService#deleteRoleIfNowOrphaned} calls this use case from inside its OWN
 * {@code @Transactional} method and catches every guard exception this method can throw, treating
 * each as "leave the role ungrouped, not a failure." With the default {@code REQUIRED} propagation,
 * that call joins the SAME physical transaction — Spring marks it rollback-only the instant this
 * method throws, and catching the exception one level up does not clear that marker: the outer
 * {@code DeleteWorkspaceTeamService#handle} would return normally, its own advice would try to
 * commit, and Spring would throw {@code UnexpectedRollbackException} instead — silently discarding
 * a team deletion that had already "succeeded," on literally every team-delete that orphans an
 * undeletable role. Reproduced with a real Spring-proxy, real-Postgres test ({@code
 * DeleteWorkspaceTeamTransactionIntegrationTest}) — a pure-Mockito unit test can never catch this,
 * since mocking {@link DeleteWorkspaceRoleUseCase} means no real transactional proxy is ever
 * involved. {@code REQUIRES_NEW} gives this method its own physical transaction: a failed attempt
 * (and only that attempt — including any partial {@link #unassignHoldersWithinWorkspace}
 * unassignments) rolls back in isolation, while the caller's own transaction (the team row already
 * deleted) is unaffected and commits normally. No behavior change for this class's other caller
 * ({@code PlatformWorkspaceController#deleteRole}, never itself inside an open transaction) —
 * {@code REQUIRES_NEW} and {@code REQUIRED} are identical when there's no transaction to join in
 * the first place.
 */
public class DeleteWorkspaceRoleService implements DeleteWorkspaceRoleUseCase {

  private final WorkspaceRoleRepository roles;
  private final WorkspaceMembershipRepository memberships;
  private final AuditEventRecorder auditEvents;
  private final EventOutboxWriter outbox;

  public DeleteWorkspaceRoleService(
      final WorkspaceRoleRepository roles,
      final WorkspaceMembershipRepository memberships,
      final AuditEventRecorder auditEvents,
      final EventOutboxWriter outbox) {
    this.roles = roles;
    this.memberships = memberships;
    this.auditEvents = auditEvents;
    this.outbox = outbox;
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void handle(final DeleteWorkspaceRoleCommand command) {
    final WorkspaceRole role =
        roles
            .findById(command.roleId())
            .orElseThrow(() -> new WorkspaceRoleNotFoundException(command.roleId()));

    final Map<UUID, WorkspaceRole> rolesById =
        roles.findAllByOrganizationId(role.organizationId()).stream()
            .collect(Collectors.toMap(WorkspaceRole::id, Function.identity()));

    // ADR-0028 §3: the reserved role is no longer permanently undeletable — a tenant may delete
    // it once some other role already carries both reserved permissions in its own effective set,
    // so the Organization never loses its own path to self-governance. Checks role definitions
    // only, not real membership coverage of the substitute — see that ADR's own open-question 2
    // for why that narrower check was the one confirmed.
    if (role.reserved() && !hasSubstituteReservedRole(command.roleId(), rolesById)) {
      throw new CannotDeleteReservedWorkspaceRoleException(command.roleId());
    }

    if (command.workspaceId() != null) {
      unassignHoldersWithinWorkspace(command.workspaceId(), role, command.force(), command.actor());
    }

    if (memberships.existsByRoleId(command.roleId())) {
      throw new WorkspaceRoleStillAssignedException(command.roleId());
    }
    // workspace_roles.parent_role_id has no ON DELETE action (see that migration's own comment) —
    // without this check, deleting a role that's still some other role's parent would instead
    // fail with a raw Postgres foreign-key violation, not this clean, typed rejection.
    final boolean hasChildRoles =
        rolesById.values().stream()
            .anyMatch(candidate -> command.roleId().equals(candidate.parentRoleId()));
    if (hasChildRoles) {
      throw new WorkspaceRoleHasChildRolesException(command.roleId());
    }

    roles.deleteById(command.roleId());

    auditEvents.write(
        command.actor(),
        "workspace_role.deleted",
        "WorkspaceRole",
        role.id().toString(),
        "organizationId=" + role.organizationId());

    outbox.write(
        "WorkspaceRole",
        "workspace_role.deleted",
        role.id(),
        role.organizationId(),
        WorkspaceRoleDeletedEvent.of(role.id(), role.organizationId()));
  }

  private void unassignHoldersWithinWorkspace(
      final UUID workspaceId,
      final WorkspaceRole role,
      final boolean force,
      final AuditActor actor) {
    final List<WorkspaceMembership> holders =
        memberships.findAllByWorkspaceId(workspaceId).stream()
            .filter(membership -> role.id().equals(membership.roleId()))
            .toList();
    for (final WorkspaceMembership membership : holders) {
      // Live UX request, 2026-09-29: force skips this guard — see
      // DeleteWorkspaceRoleCommand#force()'s own Javadoc.
      if (!force) {
        ManageMembersGuard.assertActionKeepsAtLeastOneHolder(
            memberships,
            roles,
            workspaceId,
            role.organizationId(),
            membership.roleId(),
            null,
            () -> new CannotDemoteLastAdminException(workspaceId));
      }

      final WorkspaceMembership updated = membership.withRoleId(null);
      memberships.save(updated);

      auditEvents.write(
          actor,
          "workspace_membership.role_changed",
          "WorkspaceMembership",
          updated.id().toString(),
          "previousRoleId=" + role.id() + " newRoleId=null (role deleted)");

      outbox.write(
          "WorkspaceMembership",
          "workspace_membership.role_changed",
          updated.id(),
          role.organizationId(),
          WorkspaceMemberRoleChangedEvent.of(
              updated.id(), updated.workspaceId(), updated.accountId(), role.id(), null));
    }
  }

  private static boolean hasSubstituteReservedRole(
      final UUID roleId, final Map<UUID, WorkspaceRole> rolesById) {
    return rolesById.values().stream()
        .filter(candidate -> !candidate.id().equals(roleId))
        .anyMatch(
            candidate ->
                WorkspaceRoleHierarchy.effectivePermissions(candidate.id(), rolesById)
                    .containsAll(ReservedWorkspacePermissions.ALL));
  }
}
