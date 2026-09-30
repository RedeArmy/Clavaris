package com.clavaris.organization.application.usecase.addaccessrestrictionentry;

/** Thrown when the same normalized identifier already has an entry for this Organization. */
public final class DuplicateAccessRestrictionEntryException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public DuplicateAccessRestrictionEntryException(final String identifier) {
    super("Access restriction entry already exists for identifier: " + identifier);
  }

  /**
   * TD-SEC-060: same message, plus the low-level exception that revealed the conflict (a lost race
   * against {@code ux_access_restriction_entries_organization_id_identifier}) — preserves its stack
   * trace instead of discarding it, same precedent {@code EmailAlreadyRegisteredException}'s own
   * two-constructor shape already establishes.
   */
  public DuplicateAccessRestrictionEntryException(final String identifier, final Throwable cause) {
    super("Access restriction entry already exists for identifier: " + identifier, cause);
  }
}
