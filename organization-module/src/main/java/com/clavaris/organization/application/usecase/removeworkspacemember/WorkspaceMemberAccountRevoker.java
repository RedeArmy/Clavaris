package com.clavaris.organization.application.usecase.removeworkspacemember;

import java.util.UUID;

/**
 * TD-WS-002 (closed, 2026-09-29): the rest of the "full BR-ID-03-shaped revocation cascade" {@link
 * WorkspaceMemberRefreshTokenRevoker}'s own Javadoc deliberately withheld — the domain {@code
 * Session} row, the SAS-managed access/ID token, and the hosted-login page's own browser session.
 * Kept as a second port rather than widened onto the first: the two closure decisions rest on
 * different reasoning (refresh-token revocation was always unconditionally safe; this one is safe
 * specifically because of today's real Account-Workspace 1:1 invariant, below) and should stay
 * independently revertable if that invariant ever changes, without touching the always-safe half.
 *
 * <p>Safe to call today because {@code AddWorkspaceMemberService}'s own Javadoc (cited directly by
 * {@code WorkspaceRoleClaimsCustomizer}) documents that an Account can only ever belong to one
 * Workspace right now — every add-member call provisions a brand-new Account, and no flow attaches
 * an existing one to a second Workspace. Removing a member from their one and only Workspace really
 * does mean "this Account has no more standing anywhere in this Organization right now," so
 * revoking its entire live session/token is correct, not merely convenient. TD-WS-005 (new,
 * `technical-debt-register.md` §3) tracks the one real consequence: the moment v1.1 ships
 * multi-workspace membership, this call site — and this port's own implementation — will need to
 * check for other surviving memberships before revoking, or a member removed from one Workspace
 * would lose access to every other Workspace they still legitimately belong to.
 */
@FunctionalInterface
public interface WorkspaceMemberAccountRevoker {

  void revokeAllAccessFor(UUID accountId);
}
