package com.clavaris.clientregistry.application.usecase.deleteoauthclient;

/**
 * Live UX request, 2026-09-24: permanent deletion is only ever allowed on an already-deactivated
 * client — an owner must consciously deactivate first, confirming they've already accepted losing
 * the ability to authenticate end users with it, before this genuinely irreversible action becomes
 * reachable at all. Mapped to {@code 409 Conflict} by {@code PlatformDeleteOAuthClientController},
 * same status {@code ConcurrentClientModificationException} already uses for "the request is
 * well-formed, but the resource's current state rejects it."
 */
public final class OAuthClientActiveException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public OAuthClientActiveException(final String clientId) {
    super("OAuthClient " + clientId + " must be deactivated before it can be permanently deleted");
  }
}
