package com.clavaris.clientregistry.application.usecase.rotateoauthclientsecret;

/** Same rationale as {@code createorganizationclient.OrganizationClientNotFoundException}. */
public final class OAuthClientNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public OAuthClientNotFoundException(final String clientId) {
    super("No OAuthClient exists with clientId " + clientId);
  }
}
