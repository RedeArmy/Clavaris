package com.clavaris.identity.domain.model;

/**
 * data-model.md §2: account status. Note there is deliberately no {@code PENDING_VERIFICATION}
 * state — a newly registered account is {@link #ACTIVE} immediately; whether its email has been
 * verified is tracked separately via {@code Account.emailVerifiedAt} (nullable), not by gating the
 * account's usability on it. {@code DELETED} is transitional at most in v1 (BR-DATA-03: hard
 * delete), present mainly for audit-log correlation before physical removal completes.
 *
 * <p>{@code BANNED} (SDE-III review, 2026-09-19, Clerk dashboard "Users" parity) — deliberately a
 * separate state from {@link #SUSPENDED}, not a reuse of it: the two are triggered by different
 * operator actions ("Lock" vs "Ban" in the dashboard menu) and carry different intent (a ban
 * implies a policy violation, a suspension doesn't), even though both currently block sign-in
 * identically ({@code AuthenticateWithPasswordService} rejects any non-{@code ACTIVE} account) and
 * revoke live sessions/tokens identically (see {@code Account#ban()}'s own Javadoc). No database
 * migration needed for this addition — {@code accounts.status} is a plain {@code varchar(20)}, no
 * CHECK constraint restricting its values.
 *
 * <p>{@code PENDING_APPROVAL}/{@code REJECTED} (TD-FUT-019, gated self-registration): a genuinely
 * different shape of "not yet usable" than the {@code PENDING_VERIFICATION} state this class's own
 * opening paragraph explicitly declines to have — this gates the account's very existence as a
 * usable identity (an operator or the consuming application's own backend must approve it first),
 * not merely whether its email is confirmed. Set only by {@code RegisterAccountService}/{@code
 * AuthenticateWithSocialProviderService} when the owning Organization's {@code
 * AccountAuthenticationPolicy.selfRegistrationRequiresApproval()} is on — every other registration
 * path (admin-created, Workspace-provisioned) is unaffected and stays {@code ACTIVE} immediately.
 * Blocks sign-in the same unconditional "reject any non-{@code ACTIVE} account" way {@code
 * SUSPENDED}/{@code BANNED} already do at every {@code AuthenticateWith*Service} that checks
 * status. {@code REJECTED} is terminal (no further transition back to {@code PENDING_APPROVAL}) but
 * deliberately distinct from {@code BANNED} — a rejected signup was never live, so it carries none
 * of {@code BANNED}'s "this account did something wrong" connotation. No database migration needed
 * for either value, same reasoning as {@code BANNED}'s own addition.
 */
public enum AccountStatus {
  ACTIVE,
  SUSPENDED,
  BANNED,
  DELETED,
  PENDING_APPROVAL,
  REJECTED
}
