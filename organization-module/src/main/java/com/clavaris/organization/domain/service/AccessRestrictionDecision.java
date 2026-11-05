package com.clavaris.organization.domain.service;

/**
 * TD-FUT-069: what {@link AccessRestrictionPolicy#evaluate} decided and why, so an operator who is
 * asked "why was this address refused" can be answered without trying the registration. {@code
 * matchedIdentifier} is the list entry that decided it, or {@code null} when none did (nothing is
 * restricted, the address is on no list, or an allowlist exists and the address matched none of
 * it).
 */
public record AccessRestrictionDecision(boolean allowed, Reason reason, String matchedIdentifier) {

  /** The single rule that decided; the allowlist outranks the blocklist (see the policy). */
  public enum Reason {
    NOT_RESTRICTED,
    ALLOWED_BY_ALLOWLIST,
    NOT_ON_ALLOWLIST,
    BLOCKED_BY_BLOCKLIST,
    NOT_ON_BLOCKLIST
  }
}
