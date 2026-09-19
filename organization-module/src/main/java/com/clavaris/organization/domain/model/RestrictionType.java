package com.clavaris.organization.domain.model;

/**
 * SDE-III review, 2026-09-19 — Clerk "Restrictions" parity: which list an {@link
 * AccessRestrictionEntry} belongs to. See {@code
 * com.clavaris.organization.domain.service.AccessRestrictionPolicy}'s own Javadoc for how the two
 * interact (allowlist wins outright once any entry exists, same behavior Clerk's own dashboard
 * documents).
 */
public enum RestrictionType {
  BLOCKLIST,
  ALLOWLIST
}
