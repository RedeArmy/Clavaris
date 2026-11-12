package com.clavaris.organization.infrastructure.adapter.in.web;

/**
 * TD-SEC-094: why an add to an Organization's access-restriction lists was refused with 409. The
 * two refusals used to share an empty body, so a bulk-import client could not tell "already there,
 * skip it" from "the list is full, stop" and would retry or swallow the second. The status stays
 * 409 for both so nothing that checks it breaks; the stable {@code error} code tells them apart.
 *
 * @param error {@code entry_already_exists} or {@code entry_limit_reached}
 * @param message a sentence for a person reading a log; clients must switch on {@code error}
 */
public record AccessRestrictionConflictResponse(String error, String message) {

  /* package */ static AccessRestrictionConflictResponse alreadyExists(final String identifier) {
    return new AccessRestrictionConflictResponse(
        "entry_already_exists", "An entry for " + identifier + " already exists.");
  }

  /* package */ static AccessRestrictionConflictResponse limitReached(final int limit) {
    return new AccessRestrictionConflictResponse(
        "entry_limit_reached",
        "The Organization already has " + limit + " entries, the most it can hold.");
  }
}
