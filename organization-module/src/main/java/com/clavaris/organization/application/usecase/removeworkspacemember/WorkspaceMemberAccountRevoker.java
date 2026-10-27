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
 * <p>TD-WS-005 (closed, 2026-10-27): safe to call when the removal leaves the Account with no
 * Workspace. This used to be called unconditionally, which was correct only while {@code
 * AddWorkspaceMemberService} guaranteed an Account belongs to at most one Workspace (every add
 * provisioned a brand-new Account, no flow attached an existing one to a second). {@code
 * RemoveWorkspaceMemberService} now checks for other surviving memberships first and skips this
 * call while one remains, so a member removed from one Workspace keeps its session and tokens for
 * the others; the refresh-token revocation beside it needs no such check.
 */
@FunctionalInterface
public interface WorkspaceMemberAccountRevoker {

  void revokeAllAccessFor(UUID accountId);
}
