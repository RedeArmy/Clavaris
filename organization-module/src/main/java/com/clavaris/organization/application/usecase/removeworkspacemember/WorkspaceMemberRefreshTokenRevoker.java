package com.clavaris.organization.application.usecase.removeworkspacemember;

import java.util.UUID;

/**
 * TD-WS-002 mitigation (2026-09-06, SDE-III implementation pass): a real, narrow reduction of the
 * exposure window BR-WS-03 already documents honestly — not the full workspace-scoped-token
 * architecture that row's own text names as out of v1 scope, and deliberately not that.
 *
 * <p>Reuses BR-ID-03's own refresh-token revocation entirely (identity-module's {@code
 * RefreshTokenRepository#revokeAllActiveForAccount}), triggered from a new call site rather than
 * inventing a new mechanism — same "100% reuse of an already-built, already-tested capability"
 * precedent {@link
 * com.clavaris.organization.application.usecase.addworkspacemember.AccountProvisioner#deprovision}
 * already established for {@code AddWorkspaceMemberService}'s own compensating action.
 *
 * <p><b>Deliberately narrower than a full session/token revocation cascade</b> — this does NOT call
 * {@code AccountTokenRevoker}/{@code AccountSessionRevoker} (the SAS-managed access/ID token or the
 * hosted-login-page's own browser session), unlike {@code RotateRefreshTokenService}'s own BR-ID-03
 * reuse cascade. An Account belonging to more than one Workspace is documented as a v1.1+
 * possibility, not a permanently-fixed 1:1 relationship ({@code WorkspaceRoleClaimsCustomizer}'s
 * own Javadoc) — revoking the account's entire session on a single Workspace removal would bake in
 * that temporary invariant as if it were permanent, and would be strictly more disruptive than this
 * row's own stated problem calls for. Leaving the current access token to expire naturally (SAS's
 * own default TTL, 5 minutes) bounds the real exposure window to that TTL instead of the token's
 * full refresh lifetime — a genuine, measurable improvement without touching architecture.
 *
 * <p>Deliberately does not reference identity-module's {@code AccountId}/{@code
 * RefreshTokenRepository} directly — organization-module and identity-module stay mutually
 * independent business modules, same convention {@link
 * com.clavaris.organization.application.usecase.addworkspacemember.AccountProvisioner} already
 * establishes for an identical cross-module need. Implemented in {@code app} by delegating to
 * identity-module's own {@code RefreshTokenRepository}.
 */
@FunctionalInterface
public interface WorkspaceMemberRefreshTokenRevoker {

  void revokeAllRefreshTokensFor(UUID accountId);
}
