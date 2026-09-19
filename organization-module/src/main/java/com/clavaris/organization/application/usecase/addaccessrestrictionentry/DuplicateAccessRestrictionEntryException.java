package com.clavaris.organization.application.usecase.addaccessrestrictionentry;

/** Thrown when the same normalized identifier already has an entry for this Organization. */
public final class DuplicateAccessRestrictionEntryException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public DuplicateAccessRestrictionEntryException(final String identifier) {
    super("Access restriction entry already exists for identifier: " + identifier);
  }
}
