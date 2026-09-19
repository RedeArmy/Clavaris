package com.clavaris.organization.application.usecase.removeaccessrestrictionentry;

import java.util.UUID;

/** Thrown when {@code entryId} doesn't exist, or belongs to a different Organization. */
public final class AccessRestrictionEntryNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public AccessRestrictionEntryNotFoundException(final UUID entryId) {
    super("No access restriction entry exists with id: " + entryId);
  }
}
