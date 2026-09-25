package com.clavaris.clientregistry.application.usecase.updateoauthclientgranttypes;

/** Same rationale as {@code updateoauthclientredirectsettings.OAuthClientInactiveException}. */
public final class OAuthClientInactiveException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public OAuthClientInactiveException(final String clientId) {
    super("OAuthClient " + clientId + " is inactive — reactivate it before editing grant types");
  }
}
