package com.clavaris.identity.application.usecase.updateaccountprofilepicture;

/** The uploaded content failed {@link UpdateAccountProfilePictureService}'s own validation. */
public final class InvalidProfilePictureException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public InvalidProfilePictureException(final String reason) {
    super(reason);
  }
}
