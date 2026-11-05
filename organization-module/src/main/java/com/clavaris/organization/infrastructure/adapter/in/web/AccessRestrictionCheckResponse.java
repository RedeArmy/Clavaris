package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.organization.domain.service.AccessRestrictionDecision;

/**
 * The answer to a dry run (TD-FUT-069). {@code reason} is a stable code a script can switch on;
 * {@code matchedIdentifier} is the list entry that decided it, or {@code null} when none did.
 */
public record AccessRestrictionCheckResponse(
    boolean allowed, String reason, String matchedIdentifier) {

  /* package */ static AccessRestrictionCheckResponse from(
      final AccessRestrictionDecision decision) {
    return new AccessRestrictionCheckResponse(
        decision.allowed(), decision.reason().name(), decision.matchedIdentifier());
  }
}
