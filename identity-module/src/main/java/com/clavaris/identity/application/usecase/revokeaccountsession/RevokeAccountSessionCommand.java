package com.clavaris.identity.application.usecase.revokeaccountsession;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.domain.model.AccountId;

/**
 * @param accountId the session's own owner — see {@link #RevokeAccountSessionCommand(AccountId,
 *     String)}'s own Javadoc for the self-service caller's guarantee about this value; {@code
 *     PlatformAccountSessionsAdminController} (SDE-III review, 2026-09-21) is a second, legitimate
 *     caller where this genuinely is client input (a path variable), gated by that controller's own
 *     {@code PlatformAccountOrganizationAccess} organization-ownership check before this command is
 *     ever built.
 * @param sessionId the raw {@code HttpSession} id the caller wants revoked, taken from a submitted
 *     form field — client input either way, not trusted to actually belong to {@code accountId}
 *     until {@link RevokeAccountSessionService} checks it.
 * @param actor SDE-III review, 2026-09-21 — same rationale {@code
 *     UpdateAccountProfilePictureCommand}'s own identical field documents: previously hardcoded to
 *     {@code AuditActor.account(...)} inside {@link RevokeAccountSessionService}, a real bug once
 *     an operator-driven caller existed.
 */
public record RevokeAccountSessionCommand(AccountId accountId, String sessionId, AuditActor actor) {

  /**
   * Self-service shape — {@code accountId} here is always the caller's own resolved session
   * principal ({@code AccountSessionsController}'s own {@code CurrentAccountResolver} call), never
   * client input. Defaults {@code actor} to {@code AuditActor.account(accountId)}.
   */
  public RevokeAccountSessionCommand(final AccountId accountId, final String sessionId) {
    this(accountId, sessionId, AuditActor.account(accountId.value()));
  }
}
