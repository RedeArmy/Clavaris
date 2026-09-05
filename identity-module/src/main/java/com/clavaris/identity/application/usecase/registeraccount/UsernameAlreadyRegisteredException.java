package com.clavaris.identity.application.usecase.registeraccount;

import com.clavaris.identity.domain.model.OrganizationId;

/**
 * ADR-0024 §4: same {@code (organizationId, username)} scoping, same BR-DATA-01 "never the raw
 * value in a message" discipline, as {@link EmailAlreadyRegisteredException}'s own identical
 * reasoning for its sibling identifier.
 */
public final class UsernameAlreadyRegisteredException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public UsernameAlreadyRegisteredException(final OrganizationId organizationId) {
    super("This username is already registered in organization " + organizationId.value());
  }
}
