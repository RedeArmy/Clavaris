package com.clavaris.clientregistry.application.usecase.activateoauthclient;

/**
 * Live UX request, 2026-09-25: reactivation now rotates the client secret in the same operation — a
 * deactivated client's own secret may have been the reason it was deactivated in the first place
 * (suspected compromise), or simply forgotten by the consuming application in the meantime, so
 * resurrecting the old one on reactivation would be the wrong default either way.
 *
 * @param rawSecret shown exactly once, at reactivation — same discipline as {@code
 *     registeroauthclient.RegisterOAuthClientResult#rawClientSecret()}/{@code
 *     rotateoauthclientsecret.RotateOAuthClientSecretResult#rawSecret()}.
 */
public record ActivateOAuthClientResult(String clientId, String rawSecret) {

  @Override
  public String toString() {
    return "ActivateOAuthClientResult[clientId=" + clientId + ", rawSecret=[REDACTED]]";
  }
}
