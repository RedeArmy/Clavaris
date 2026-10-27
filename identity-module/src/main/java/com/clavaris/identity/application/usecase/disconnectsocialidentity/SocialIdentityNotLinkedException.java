package com.clavaris.identity.application.usecase.disconnectsocialidentity;

public class SocialIdentityNotLinkedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public SocialIdentityNotLinkedException() {
    super("That provider is not linked to this account");
  }
}
