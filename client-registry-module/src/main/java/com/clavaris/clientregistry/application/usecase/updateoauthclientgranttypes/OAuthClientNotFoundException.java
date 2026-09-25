package com.clavaris.clientregistry.application.usecase.updateoauthclientgranttypes;

/** Same rationale as {@code deactivateoauthclient.OAuthClientNotFoundException}. */
public final class OAuthClientNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public OAuthClientNotFoundException(final String clientId) {
    super("No OAuthClient exists with clientId " + clientId);
  }
}
