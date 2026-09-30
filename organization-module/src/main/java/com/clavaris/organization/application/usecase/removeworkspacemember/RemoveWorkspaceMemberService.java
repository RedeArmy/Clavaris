package com.clavaris.organization.application.usecase.removeworkspacemember;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.organization.application.usecase.addworkspacemember.ManageMembersGuard;
import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceMembershipRepository;
import com.clavaris.organization.application.usecase.createworkspace.WorkspaceRepository;
import com.clavaris.organization.application.usecase.createworkspace.WorkspaceRoleRepository;
import com.clavaris.organization.application.usecase.deleteorganization.EventOutboxWriter;
import com.clavaris.organization.domain.event.WorkspaceMemberRemovedEvent;
import com.clavaris.organization.domain.model.WorkspaceMembership;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestration for {@link RemoveWorkspaceMemberUseCase}.
 *
 * <p>BR-WS-03, stated honestly: v1 has no workspace-scoped token/authorization concept — tokens are
 * Account/OAuthClient-scoped per Organization, not per-Workspace (`domain-model.md`). "Immediate
 * access revocation" in v1 means the admin-API listing and this method's own outbox event fire
 * synchronously with the delete below, not that a live token is invalidated in the full sense this
 * rule's own title implies. A consuming application that gates its own authorization by workspace
 * membership must still re-check membership itself (or subscribe to this event) — documented as a
 * forward-looking limitation, not silently glossed over.
 *
 * <p><b>TD-WS-002 (closed, 2026-09-29):</b> this method revokes every active {@code RefreshToken}
 * for the removed member's Account ({@link WorkspaceMemberRefreshTokenRevoker}, 2026-09-06) and now
 * also the domain {@code Session} row, the SAS-managed access/ID token, and the hosted-login page's
 * own browser session ({@link WorkspaceMemberAccountRevoker}, 2026-09-29) — see that port's own
 * Javadoc for exactly why the second half is safe to do unconditionally today (the real
 * Account-Workspace 1:1 invariant) and what changes the moment that invariant doesn't hold anymore
 * (TD-WS-005). Together this is the same "full BR-ID-03-shaped cascade" {@code
 * RotateRefreshTokenService}'s own reuse response already runs, not the full workspace-scoped-token
 * architecture BR-WS-03's own text still correctly names as out of v1 scope — that remains a
 * separate, larger gap (a consuming application still can't get a webhook-driven live revocation of
 * a *different* Workspace's own resources this Account might still legitimately access).
 */
public class RemoveWorkspaceMemberService implements RemoveWorkspaceMemberUseCase {

  private final WorkspaceMembershipRepository memberships;
  private final WorkspaceRepository workspaces;
  private final WorkspaceRoleRepository roles;
  private final AuditEventRecorder auditEvents;
  private final EventOutboxWriter outbox;

  // PMD.LongVariable: refreshTokenRevoker names exactly what it is — same convention every other
  // descriptively-named port in this codebase follows (e.g. RotateRefreshTokenService's own
  // identical accountTokenRevoker/accountSessionRevoker fields).
  @SuppressWarnings("PMD.LongVariable")
  private final WorkspaceMemberRefreshTokenRevoker refreshTokenRevoker;

  private final WorkspaceMemberAccountRevoker accountRevoker;

  // java:S107: one parameter per collaborating port — same rationale as AddWorkspaceMemberService's
  // own identical suppression; TD-WS-002's own closure added the one new port (accountRevoker) to
  // an already-wide constructor rather than inventing a narrower one.
  @SuppressWarnings("java:S107")
  public RemoveWorkspaceMemberService(
      final WorkspaceMembershipRepository memberships,
      final WorkspaceRepository workspaces,
      final WorkspaceRoleRepository roles,
      final AuditEventRecorder auditEvents,
      final EventOutboxWriter outbox,
      @SuppressWarnings("PMD.LongVariable")
          final WorkspaceMemberRefreshTokenRevoker refreshTokenRevoker,
      final WorkspaceMemberAccountRevoker accountRevoker) {
    this.memberships = memberships;
    this.workspaces = workspaces;
    this.roles = roles;
    this.auditEvents = auditEvents;
    this.outbox = outbox;
    this.refreshTokenRevoker = refreshTokenRevoker;
    this.accountRevoker = accountRevoker;
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

    // webhook-module's own EventOutboxWriter needs organizationId — resolved before the delete
    // below (and before the guard, which needs it to load this Organization's own roles) so a
    // concurrently-deleted Workspace (no v1 use case does this today, but nothing at this layer
    // forbids it) can't leave this lookup with nothing to find.
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

    // ADR-0027 §2: ManageMembersGuard replaces LastAdminGuard — removal is a "reassign to null"
    // for this check's purposes, same as ChangeWorkspaceMemberRoleService's own unassign path.
    ManageMembersGuard.assertActionKeepsAtLeastOneHolder(
        memberships,
        roles,
        command.workspaceId(),
        organizationId,
        membership.roleId(),
        null,
        () -> new CannotRemoveLastAdminException(command.workspaceId()));

    memberships.deleteById(membership.id());

    // TD-WS-002 (closed): same transaction as the membership delete above — every revoked row
    // (identity-module) and the WorkspaceMembership delete (organization-module) live in this one
    // deployable's own single persistence unit, so there is no cross-database atomicity concern
    // here, unlike AccountProvisioner's own deliberately-outside-the-transaction network call. A
    // crash between the writes is not a real risk this way — either all commit or none do.
    refreshTokenRevoker.revokeAllRefreshTokensFor(command.accountId());
    accountRevoker.revokeAllAccessFor(command.accountId());

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
