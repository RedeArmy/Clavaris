package com.clavaris.organization.application.usecase.addaccessrestrictionentry;

import java.util.UUID;

/**
 * An Organization already holds the most access-list entries it may (TD-SEC-087). Every
 * registration loads the whole list to evaluate it, so an unbounded list is a cost on each of them;
 * the cap is generous for a real blocklist or allowlist and a stop for a runaway import.
 */
public final class AccessRestrictionLimitExceededException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public AccessRestrictionLimitExceededException(final UUID organizationId, final int limit) {
    super(
        "Organization "
            + organizationId
            + " already has the maximum of "
            + limit
            + " access-list entries");
  }
}
