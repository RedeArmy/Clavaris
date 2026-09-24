package com.clavaris.clientregistry.application.usecase.updateoauthclientredirectsettings;

/**
 * Live UX bug fix, 2026-09-24: a deactivated {@code OAuthClient} could still have its redirect
 * settings edited — the same "nothing about an inactive client should be mutable" invariant {@code
 * DeactivateOAuthClientService} already enforces implicitly (no reactivation existed, so no further
 * edit made sense either) was never actually checked here. Mapped to {@code 409 Conflict} by {@code
 * PlatformOAuthClientController} — same status {@code ConcurrentClientModificationException}
 * already uses for "the request is well-formed, but the resource's current state rejects it."
 */
public final class OAuthClientInactiveException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public OAuthClientInactiveException(final String clientId) {
    super(
        "OAuthClient "
            + clientId
            + " is inactive — reactivate it before editing redirect settings");
  }
}
