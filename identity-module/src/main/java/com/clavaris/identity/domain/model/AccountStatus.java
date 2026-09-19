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
 */
public enum AccountStatus {
  ACTIVE,
  SUSPENDED,
  BANNED,
  DELETED
}
