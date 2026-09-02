package com.clavaris.identity.application.usecase.purgesigningkeyfororganization;

import com.clavaris.identity.domain.model.OrganizationId;

/**
 * @param replacementKid non-null only when the purged key was the currently-active one — the
 *     Organization must never be left without an active key, so a replacement is generated and
 *     activated first in that case (same mechanism as ordinary rotation, TD-SEC-008). {@code null}
 *     when the purged key was already retired (an old, already-rotated-away key discovered
 *     compromised after the fact) — the Organization's own separate, still-active key is untouched,
 *     nothing to replace.
 */
public record PurgeSigningKeyForOrganizationResult(
    OrganizationId organizationId, String purgedKid, String replacementKid) {}
