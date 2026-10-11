package com.clavaris.identity.application.usecase.authenticatewithsocialprovider;

/**
 * The Organization blocks email subaddresses for this provider and the email the provider returned
 * is one. Refused like any other social login the Organization does not allow: the person sees the
 * generic sign-in error, never which rule it was.
 */
public final class EmailSubaddressNotAllowedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public EmailSubaddressNotAllowedException() {
    super("This Organization does not allow email subaddresses for social sign-in");
  }
}
