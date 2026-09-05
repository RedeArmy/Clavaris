package com.clavaris.clientregistry.application.usecase.setredirectpolicyforclient;

import java.util.UUID;

/**
 * Also thrown when the path's {@code organizationId} doesn't match the resolved {@code
 * OAuthClient}'s own {@code organizationId} — same "collapse a cross-tenant mismatch into the same
 * 404 a genuinely missing id would produce" discipline BR-ORG-02 already establishes elsewhere,
 * rather than leaking which organizationId/oauthClientId combination is real.
 */
public final class OAuthClientNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public OAuthClientNotFoundException(final UUID oauthClientId) {
    super("No OAuthClient exists with id " + oauthClientId);
  }
}
