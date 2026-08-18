package com.clavaris.identity.domain.model;

/**
 * data-model.md §2: account status. Note there is deliberately no {@code PENDING_VERIFICATION}
 * state — a newly registered account is {@link #ACTIVE} immediately; whether its email has been
 * verified is tracked separately via {@code Account.emailVerifiedAt} (nullable), not by gating the
 * account's usability on it. {@code DELETED} is transitional at most in v1 (BR-DATA-03: hard
 * delete), present mainly for audit-log correlation before physical removal completes.
 */
public enum AccountStatus {
  ACTIVE,
  SUSPENDED,
  DELETED
}
