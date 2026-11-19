package com.clavaris.common.domain.model;

/**
 * BR-ADMIN-03 / ADR-0048: a value a caller sent that a domain rule refuses, as opposed to a defect.
 * It is still an {@link IllegalArgumentException}, so every caller that already expects one keeps
 * working, but it is a type of its own so that the REST layer can answer 400 for exactly this and
 * for nothing else: mapping every {@code IllegalArgumentException} to 400 would turn a real defect
 * (a null that should never have got there) into a client error nobody investigates.
 *
 * <p>A domain rule that validates what a client sent throws a subclass of this, and its message may
 * quote the offending value, so the REST layer never echoes it back.
 */
public class InvalidInputException extends IllegalArgumentException {

  private static final long serialVersionUID = 1L;

  public InvalidInputException(final String message) {
    super(message);
  }

  public InvalidInputException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
