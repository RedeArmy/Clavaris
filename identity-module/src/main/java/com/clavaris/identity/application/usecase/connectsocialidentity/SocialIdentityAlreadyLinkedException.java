package com.clavaris.identity.application.usecase.connectsocialidentity;

public class SocialIdentityAlreadyLinkedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public SocialIdentityAlreadyLinkedException() {
    super("This provider account is already linked");
  }

  public SocialIdentityAlreadyLinkedException(final Throwable cause) {
    super("This provider account is already linked", cause);
  }
}
