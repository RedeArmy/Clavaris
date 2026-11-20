package com.clavaris.clientregistry.application.usecase.updateoauthclientaccesstokenttl;

public final class OAuthClientInactiveException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public OAuthClientInactiveException(final String clientId) {
    super(
        "OAuthClient "
            + clientId
            + " is inactive — reactivate it before editing the access-token lifetime");
  }
}
