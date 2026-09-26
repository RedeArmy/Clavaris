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
 * <p><b>TD-WS-002 mitigation (2026-09-06):</b> this method now also revokes every active {@code
 * RefreshToken} for the removed member's Account — see {@link WorkspaceMemberRefreshTokenRevoker}'s
 * own Javadoc for exactly why this is a real, measurable exposure-window reduction (bounds it to
 * one access-token TTL instead of the token's full refresh lifetime) and deliberately not the full
 * workspace-scoped-token architecture BR-WS-03's own text still correctly names as out of v1 scope.
 * A currently-live access token is deliberately left to expire naturally, not force-revoked
 * alongside it — same reasoning that port's own Javadoc documents in full.
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

  @SuppressWarnings("java:S107") // one parameter per collaborating port — same rationale as
  // AddWorkspaceMemberService's own identical suppression.
  public RemoveWorkspaceMemberService(
      final WorkspaceMembershipRepository memberships,
      final WorkspaceRepository workspaces,
      final WorkspaceRoleRepository roles,
      final AuditEventRecorder auditEvents,
      final EventOutboxWriter outbox,
      @SuppressWarnings("PMD.LongVariable")
          final WorkspaceMemberRefreshTokenRevoker refreshTokenRevoker) {
    this.memberships = memberships;
    this.workspaces = workspaces;
    this.roles = roles;
    this.auditEvents = auditEvents;
    this.outbox = outbox;
    this.refreshTokenRevoker = refreshTokenRevoker;
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

    // TD-WS-002 mitigation: same transaction as the membership delete above — both RefreshToken
    // (identity-module) and WorkspaceMembership (organization-module) rows live in this one
    // deployable's own single persistence unit, so there is no cross-database atomicity concern
    // here, unlike AccountProvisioner's own deliberately-outside-the-transaction network call. A
    // crash between the two writes is not a real risk this way — either both commit or neither
    // does.
    refreshTokenRevoker.revokeAllRefreshTokensFor(command.accountId());

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
