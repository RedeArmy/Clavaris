package com.clavaris.clientregistry.application.usecase.verifyclientdomainownership;

import java.util.UUID;

/**
 * Thrown when an operator asks to verify a domain for an {@code OAuthClient} that never requested
 * one (no {@code ClientDomainConfig} row at all — still {@code SHARED} mode) — there is nothing to
 * verify, distinct from {@link OAuthClientNotFoundException} (the client itself doesn't exist).
 */
public final class ClientDomainConfigNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public ClientDomainConfigNotFoundException(final UUID oauthClientId) {
    super("No domain has ever been requested for OAuthClient " + oauthClientId);
  }
}
