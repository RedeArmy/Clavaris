package com.clavaris.identity.application.usecase.connectsocialidentity;

/** BR-ID-12: the Organization has not enabled this provider. */
public class SocialConnectionNotAllowedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public SocialConnectionNotAllowedException() {
    super("This sign-in provider is not enabled for the organization");
  }
}
