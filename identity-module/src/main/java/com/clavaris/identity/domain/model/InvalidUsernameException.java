package com.clavaris.identity.domain.model;

import com.clavaris.common.domain.model.InvalidInputException;

/** BR-ADMIN-03: a username that is not 3 to 32 letters, digits, underscores or hyphens. */
public final class InvalidUsernameException extends InvalidInputException {

  private static final long serialVersionUID = 1L;

  public InvalidUsernameException(final String message) {
    super(message);
  }
}
