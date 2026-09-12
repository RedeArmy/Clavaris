package com.clavaris.clientregistry.application.usecase.rotateoauthclientsecret;

/**
 * @param rawSecret shown exactly once, at rotation — same discipline as {@code
 *     registeroauthclient.RegisterOAuthClientResult#rawClientSecret()}.
 */
public record RotateOAuthClientSecretResult(String clientId, String rawSecret) {

  @Override
  public String toString() {
    return "RotateOAuthClientSecretResult[clientId=" + clientId + ", rawSecret=[REDACTED]]";
  }
}
