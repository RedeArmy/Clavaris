package com.clavaris.clientregistry.application.usecase.setclientbranding;

import java.util.UUID;

/**
 * Also thrown when the path's {@code organizationId} doesn't match the resolved {@code
 * OAuthClient}'s own {@code organizationId} — same rationale as this module's sibling in {@code
 * setredirectpolicyforclient}.
 */
public final class OAuthClientNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public OAuthClientNotFoundException(final UUID oauthClientId) {
    super("No OAuthClient exists with id " + oauthClientId);
  }
}
